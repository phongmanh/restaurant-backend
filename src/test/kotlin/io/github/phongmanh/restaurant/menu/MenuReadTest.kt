package io.github.phongmanh.restaurant.menu

import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.obj
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** IT-MENU-01 to IT-MENU-04, and IT-MENU-10's menu half (menu §2, §4; FR-002, FR-024). */
class MenuReadTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val e = Endpoints.Menu

    @Test
    fun `IT-MENU-01 guests see active categories, items and options in order, sold-out items flagged, prices as Money`() =
        menuApp {
            val a = Tenants.create("A", "manager@a.example")
            val t = a.managerToken
            val noodles = client.category(t, "Phở")
            val hiddenCat = client.category(t, "Bí mật", extra = """, "active": false""")
            val drinks = client.category(t, "Đồ uống")
            val options =
                """, "option_groups": [{"name_vi": "Cỡ", "min_choices": 1, "max_choices": 1, "options": [
                     {"name_vi": "Nhỏ"}, {"name_vi": "Lớn", "price_delta": {"amount": "10000", "currency": "VND"}},
                     {"name_vi": "Hết", "active": false}]}]"""
            val pho = client.item(t, noodles, "Phở bò", extra = options).str("id")
            client.item(t, noodles, "Ẩn", extra = """, "active": false""")
            val bun = client.item(t, noodles, "Bún chả").str("id")
            client.item(t, hiddenCat, "Không thấy")
            val tea = client.item(t, drinks, "Trà đá", price = "5000").str("id")
            client.api(e.SET_MENU_ITEM_SOLD_OUT, t, "itemId" to bun, body = """{"sold_out": true}""")

            val menu = client.guestMenu(a.id).json()
            assertEquals("vi", menu.str("lang"))
            val categories = menu.array("categories").objects()
            assertEquals(listOf("Phở", "Đồ uống"), categories.map { it.str("name") })
            val items = categories[0].array("items").objects()
            assertEquals(listOf(pho, bun), items.map { it.str("id") })
            assertEquals(listOf("false", "true"), items.map { it.str("sold_out") })
            assertEquals("""{"amount":"65000","currency":"VND"}""", items[0].obj("price").toString())
            val group = items[0].array("option_groups").objects().single()
            assertEquals(listOf("Nhỏ", "Lớn"), group.array("options").objects().map { it.str("name") })
            assertEquals(
                "10000",
                group
                    .array("options")
                    .objects()[1]
                    .obj("price_delta")
                    .str("amount"),
            )
            assertEquals(
                tea,
                categories[1]
                    .array("items")
                    .objects()
                    .single()
                    .str("id"),
            )
            assertFalse("version\"" in items[0].toString(), "no row versions for guests")
        }

    @Test
    fun `IT-MENU-02 the guest read needs a session and is always the session's restaurant`() =
        menuApp {
            val a = Tenants.create("A", "manager@a.example")
            val b = Tenants.create("B", "manager@b.example")
            client.category(a.managerToken, "Phở của A")
            client.category(b.managerToken, "Cơm của B")
            assertEquals("unauthenticated", client.api(e.GET_GUEST_MENU, null).problem())
            assertEquals("unauthenticated", client.api(e.GET_GUEST_MENU, "not-a-token").problem())
            assertEquals("unauthenticated", client.api(e.GET_GUEST_MENU, b.managerToken).problem(), "a staff token isn't a guest's")
            val names =
                client
                    .guestMenu(a.id, "?restaurant_id=${b.id}")
                    .json()
                    .array("categories")
                    .objects()
                    .map { it.str("name") }
            assertEquals(listOf("Phở của A"), names)
        }

    @Test
    fun `IT-MENU-03 the current ETag gets 304, and any write, sold out too, a new one`() =
        menuApp {
            val a = Tenants.create("A", "manager@a.example")
            val (_, kitchen) = a.member("kitchen@a.example", "kitchen")
            val cat = client.category(a.managerToken, "Phở")
            val item = client.item(a.managerToken, cat, "Phở bò").str("id")

            val first = client.guestMenu(a.id)
            val etag = first.headers[HttpHeaders.ETag]!!
            assertEquals("\"${menuVersion(a.id)}-vi\"", etag)
            assertEquals("private, no-cache", first.headers[HttpHeaders.CacheControl])
            val same = client.guestMenu(a.id, etag = etag)
            assertEquals(HttpStatusCode.NotModified, same.status)
            assertEquals("", same.bodyAsText())
            assertEquals(etag, same.headers[HttpHeaders.ETag])
            assertEquals("private, no-cache", same.headers[HttpHeaders.CacheControl])

            val staff = client.api(e.GET_STAFF_MENU, kitchen)
            val staffTag = staff.headers[HttpHeaders.ETag]!!
            assertEquals("\"${menuVersion(a.id)}\"", staffTag)
            assertEquals("private, no-cache", staff.headers[HttpHeaders.CacheControl])
            assertEquals(
                HttpStatusCode.NotModified,
                client.api(e.GET_STAFF_MENU, kitchen) { header(HttpHeaders.IfNoneMatch, staffTag) }.status,
            )

            client.api(e.SET_MENU_ITEM_SOLD_OUT, kitchen, "itemId" to item, body = """{"sold_out": true}""")
            val changed = client.guestMenu(a.id, etag = etag)
            assertEquals(HttpStatusCode.OK, changed.status)
            assertNotEquals(etag, changed.headers[HttpHeaders.ETag])
            assertEquals(
                "true",
                changed
                    .json()
                    .array("categories")
                    .objects()[0]
                    .array("items")
                    .objects()[0]
                    .str("sold_out"),
            )
            assertEquals(HttpStatusCode.OK, client.api(e.GET_STAFF_MENU, kitchen) { header(HttpHeaders.IfNoneMatch, staffTag) }.status)
            assertEquals("no-store", client.api(e.GET_MENU_ITEM, kitchen, "itemId" to item).headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `IT-MENU-04 English falls back to Vietnamese field by field, and a language the restaurant lacks is vi`() =
        menuApp {
            val a = Tenants.create("A", "manager@a.example")
            val cat = client.category(a.managerToken, "Phở", extra = """, "name_en": "Noodles"""")
            client.item(a.managerToken, cat, "Phở bò", extra = """, "description_vi": "Bò tái", "name_en": "Beef pho"""")
            client.item(a.managerToken, cat, "Phở gà", extra = """, "description_vi": "Gà ta", "description_en": "Free-range"""")

            assertEquals("vi", client.guestMenu(a.id, "?lang=en").json().str("lang"), "the restaurant offers vi only")
            AuthFixtures.sql("UPDATE restaurants SET languages = '{en,vi}' WHERE id = ?", a.id.value)
            assertEquals("en", client.guestMenu(a.id).json().str("lang"), "no lang: the restaurant's first")
            val en = client.guestMenu(a.id, "?lang=en")
            assertEquals("\"${menuVersion(a.id)}-en\"", en.headers[HttpHeaders.ETag])
            val category =
                en
                    .json()
                    .array("categories")
                    .objects()
                    .single()
            assertEquals("Noodles", category.str("name"))
            val items = category.array("items").objects().map { it.str("name") to it.str("description") }
            assertEquals(listOf("Beef pho" to "Bò tái", "Phở gà" to "Free-range"), items)
            assertEquals("vi", client.guestMenu(a.id, "?lang=fr").json().str("lang"))
            assertEquals(
                "Phở",
                client
                    .guestMenu(a.id, "?lang=vi")
                    .json()
                    .array("categories")
                    .objects()
                    .single()
                    .str("name"),
            )
        }

    @Test
    fun `staff and admins read everything not deleted in both languages, and a deleted item leaves both reads`() =
        menuApp {
            val a = Tenants.create("A", "manager@a.example")
            val (_, admin) = Tenants.admin("admin@platform.example")
            val (_, waiter) = a.member("waiter@a.example", "waiter")
            val hidden = client.category(a.managerToken, "Ẩn", extra = """, "active": false, "name_en": "Hidden"""")
            val item = client.item(a.managerToken, hidden, "Món ẩn", extra = """, "active": false""")
            val gone = client.item(a.managerToken, hidden, "Đã xoá").str("id")
            client.api(e.DELETE_MENU_ITEM, a.managerToken, "itemId" to gone) { header(HttpHeaders.IfMatch, "\"0\"") }

            val staff = client.api(e.GET_STAFF_MENU, waiter).json()
            val category = staff.array("categories").objects().single()
            assertEquals(listOf("Ẩn", "Hidden", "false"), listOf(category.str("name_vi"), category.str("name_en"), category.str("active")))
            val listed = category.array("items").objects()
            assertEquals(listOf(item.str("id")), listed.map { it.str("id") }, "the deleted item is gone, the hidden one listed")
            assertEquals("0", listed.single().str("version"))
            assertEquals(staff, client.api(e.GET_RESTAURANT_MENU, admin, "restaurantId" to a.id).json(), "the admin read is the staff body")
            assertEquals("not_found", client.api(e.GET_MENU_ITEM, waiter, "itemId" to gone).problem())
            assertTrue(
                client
                    .guestMenu(a.id)
                    .json()
                    .array("categories")
                    .isEmpty(),
            )

            assertEquals("not_found", client.api(e.GET_RESTAURANT_MENU, admin, "restaurantId" to java.util.UUID.randomUUID()).problem())
            assertEquals("not_found", client.api(e.GET_RESTAURANT_MENU, a.managerToken, "restaurantId" to a.id).problem())
            AuthFixtures.suspend(a.id)
            assertEquals("restaurant_suspended", client.api(e.GET_STAFF_MENU, waiter).problem())
            assertEquals(HttpStatusCode.OK, client.api(e.GET_RESTAURANT_MENU, admin, "restaurantId" to a.id).status)
        }
}
