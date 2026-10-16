package io.github.phongmanh.restaurant.menu

import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.persistence.DatabasePools
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.menu.application.DatabaseMenuCatalog
import io.github.phongmanh.restaurant.menu.application.LineProblem
import io.github.phongmanh.restaurant.menu.application.LineProblemCode
import io.github.phongmanh.restaurant.menu.application.LineRequest
import io.github.phongmanh.restaurant.menu.application.PricedOrder
import io.github.phongmanh.restaurant.menu.persistence.OptionRepository
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.str
import io.ktor.client.request.header
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** IT-MENU-13 (menu §7; FR-003, FR-023): the port `orders` prices its lines with. */
class MenuCatalogTest {
    private lateinit var pools: DatabasePools
    private lateinit var transactor: ExposedTransactor
    private val catalog = DatabaseMenuCatalog(OptionRepository(UuidV7))

    @BeforeTest
    fun setUp() {
        TestDatabase.truncate()
        pools = DatabasePools(TestDatabase.settings)
        transactor = ExposedTransactor(pools)
    }

    @AfterTest
    fun tearDown() = pools.close()

    private fun price(
        restaurant: RestaurantId,
        vararg lines: LineRequest,
    ): PricedOrder = runBlocking { transactor.inTransaction(restaurant) { catalog.price(this, restaurant, lines.toList()) } }

    private fun line(
        item: String,
        vararg options: String,
    ) = LineRequest(UUID.fromString(item), 2, options.map(UUID::fromString))

    /** The IDs a test prices: one item with two option groups, and one item for each way to be unorderable. */
    private class Seeded(
        val restaurant: RestaurantId,
        val pho: String,
        val small: String,
        val large: String,
        val retired: String,
        val egg: String,
        val quay: String,
        val plain: String,
        val unavailable: List<String>,
        val unknown: List<String>,
    )

    private suspend fun ApplicationTestBuilder.seed(): Seeded {
        val a = Tenants.create("A", "manager@a.example")
        val b = Tenants.create("B", "manager@b.example")
        val t = a.managerToken
        val cat = client.category(t, "Phở", extra = """, "name_en": "Noodles"""")
        val hiddenCat = client.category(t, "Ẩn", extra = """, "active": false""")
        val groups =
            """, "option_groups": [
              {"name_vi": "Cỡ", "name_en": "Size", "min_choices": 1, "max_choices": 1,
               "options": [{"name_vi": "Nhỏ"}, {"name_vi": "Lớn", "price_delta": {"amount": "10000", "currency": "VND"}},
                           {"name_vi": "Cũ", "active": false}]},
              {"name_vi": "Thêm", "min_choices": 0, "max_choices": 2,
               "options": [{"name_vi": "Trứng", "price_delta": {"amount": "5000", "currency": "VND"}}, {"name_vi": "Quẩy"}]}]"""
        val pho = client.item(t, cat, "Phở bò", extra = """, "name_en": "Beef pho"$groups""")
        val (size, extra) = pho.array("option_groups").objects().map { g -> g.array("options").objects().map { it.str("id") } }
        val soldOut = client.item(t, cat, "Hết").str("id")
        client.api(Endpoints.Menu.SET_MENU_ITEM_SOLD_OUT, t, "itemId" to soldOut, body = """{"sold_out": true}""")
        val hidden = client.item(t, cat, "Ẩn", extra = """, "active": false""").str("id")
        val inHidden = client.item(t, hiddenCat, "Trong mục ẩn").str("id")
        val otherRestaurant = client.item(b.managerToken, client.category(b.managerToken, "B"), "Của B").str("id")
        val deleted = client.item(t, cat, "Xoá").str("id")
        client.api(Endpoints.Menu.DELETE_MENU_ITEM, t, "itemId" to deleted) { header("If-Match", "\"0\"") }
        return Seeded(
            restaurant = a.id,
            pho = pho.str("id"),
            small = size[0],
            large = size[1],
            retired = size[2],
            egg = extra[0],
            quay = extra[1],
            plain = client.item(t, cat, "Trà", price = "5000").str("id"),
            unavailable = listOf(soldOut, hidden, inHidden),
            unknown = listOf(otherRestaurant, deleted),
        )
    }

    @Test
    fun `IT-MENU-13 prices come from the database, with each chosen option's names and delta`() =
        menuApp {
            val s = seed()
            val ok = price(s.restaurant, line(s.pho, s.large, s.egg), line(s.plain))
            assertTrue(ok.ok)
            val first = ok.lines[0]
            assertEquals(
                listOf("Phở bò", "Beef pho", "65000", "2"),
                listOf(first.nameVi, first.nameEn, first.price.toString(), first.quantity.toString()),
            )
            assertEquals(listOf("Lớn" to 10000L, "Trứng" to 5000L), first.options.map { it.nameVi to it.priceDelta })
            assertEquals(listOf("Cỡ", "Size"), listOf(first.options[0].groupNameVi, first.options[0].groupNameEn))
            assertEquals(80000L, first.unitTotal)
            assertEquals(5000L, ok.lines[1].unitTotal)
        }

    @Test
    fun `IT-MENU-13 every problem of every line is collected, and then nothing is priced`() =
        menuApp {
            val s = seed()
            val lines =
                s.unavailable.map { line(it) } +
                    s.unknown.map { line(it) } +
                    listOf(
                        line(s.pho),
                        line(s.pho, s.small, s.large),
                        line(s.pho, s.small, s.retired),
                        line(s.pho, s.small, s.egg, s.quay, UUID.randomUUID().toString()),
                        line(s.plain),
                    )
            val bad = price(s.restaurant, *lines.toTypedArray())
            assertTrue(bad.lines.isEmpty(), "nothing is priced when a line fails")
            assertEquals(
                listOf(
                    0 to LineProblemCode.ITEM_UNAVAILABLE,
                    1 to LineProblemCode.ITEM_UNAVAILABLE,
                    2 to LineProblemCode.ITEM_UNAVAILABLE,
                    3 to LineProblemCode.UNKNOWN_ITEM,
                    4 to LineProblemCode.UNKNOWN_ITEM,
                    5 to LineProblemCode.OPTION_REQUIRED,
                    6 to LineProblemCode.TOO_MANY_OPTIONS,
                    7 to LineProblemCode.UNKNOWN_OPTION,
                    8 to LineProblemCode.UNKNOWN_OPTION,
                ),
                bad.problems.map(LineProblem::line).zip(bad.problems.map(LineProblem::code)),
            )
            assertEquals(UUID.fromString(s.unavailable[0]), bad.problems[0].itemId)
        }
}
