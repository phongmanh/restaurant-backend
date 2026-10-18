package io.github.phongmanh.restaurant.menu

import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.errorFields
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.obj
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** IT-MENU-05 to IT-MENU-09, IT-MENU-11, IT-MENU-12 and IT-MENU-15 (menu §3, §5, FR-020, FR-021, FR-023). */
class MenuEditTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val e = Endpoints.Menu

    private companion object {
        const val ROUNDS = 5
    }

    private fun ifMatch(version: Any): io.ktor.client.request.HttpRequestBuilder.() -> Unit =
        { header(HttpHeaders.IfMatch, "\"$version\"") }

    @Test
    fun `IT-MENU-05 field limits are 400 per field, and a name is unique within its category only`() =
        menuApp {
            val a = Tenants.create("A", "manager@a.example")
            val noodles = client.category(a.managerToken, "Phở")
            val rice = client.category(a.managerToken, "Cơm")
            val long = "x".repeat(101)

            val bad =
                client.api(
                    e.CREATE_MENU_ITEM,
                    a.managerToken,
                    body =
                        """{"category_id": "$noodles", "name_vi": "$long", "description_vi": "${"d".repeat(501)}",
                           "price": {"amount": "65000.5", "currency": "VND"}}""",
                )
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertEquals(listOf("/name_vi", "/description_vi", "/price/amount"), bad.errorFields())
            val usd = """{"category_id": "$noodles", "name_vi": "Bún", "price": {"amount": "1", "currency": "USD"}}"""
            assertEquals(listOf("/price/currency"), client.api(e.CREATE_MENU_ITEM, a.managerToken, body = usd).errorFields())
            assertEquals(HttpStatusCode.Created, client.newItem(a.managerToken, noodles, "Phở").status)
            assertEquals(listOf("/price/amount"), client.newItem(a.managerToken, noodles, "Bún", price = "100000001").errorFields())
            assertEquals(listOf("/price/amount"), client.newItem(a.managerToken, noodles, "Bún", price = "-1").errorFields())
            assertEquals(
                listOf("/category_id", "/name_vi", "/price"),
                client.api(e.CREATE_MENU_ITEM, a.managerToken, body = "{}").errorFields(),
            )

            assertEquals("duplicate_name", client.newItem(a.managerToken, noodles, "  PHỞ ").problem(), "case-insensitive, trimmed")
            assertEquals(HttpStatusCode.Created, client.newItem(a.managerToken, rice, "Phở").status, "same name, another category")
            assertEquals(HttpStatusCode.Created, client.newItem(a.managerToken, noodles, "Free", price = "0").status)

            val bun = client.item(a.managerToken, noodles, "Bún bò")
            val rename =
                client.api(
                    e.UPDATE_MENU_ITEM_POST,
                    a.managerToken,
                    "itemId" to bun.str("id"),
                    body = """{"name_vi": "phở"}""",
                    configure = ifMatch(0),
                )
            assertEquals("duplicate_name", rename.problem())
            val move =
                client.api(
                    e.UPDATE_MENU_ITEM_POST,
                    a.managerToken,
                    "itemId" to bun.str("id"),
                    body = """{"category_id": "$rice"}""",
                    configure = ifMatch(0),
                )
            assertEquals(HttpStatusCode.OK, move.status, "the name is free in the other category")
            assertEquals(rice, move.json().str("category_id"))
            assertEquals("duplicate_name", client.newCategory(a.managerToken, "cơm").problem())
            val empty = client.api(e.UPDATE_MENU_ITEM_POST, a.managerToken, "itemId" to bun.str("id"), body = "{}", configure = ifMatch(1))
            assertEquals(listOf(""), empty.errorFields())
        }

    @Test
    fun `IT-MENU-05 a category's bad fields are 400 per field, on create and edit, and change nothing`() =
        menuApp {
            val a = Tenants.create("A", "manager@a.example")
            val rice = client.category(a.managerToken, "Cơm")
            val badCategory = client.newCategory(a.managerToken, "x".repeat(101), """, "name_en": "${"e".repeat(101)}"""")
            assertEquals(HttpStatusCode.BadRequest, badCategory.status)
            assertEquals(listOf("/name_vi", "/name_en"), badCategory.errorFields())
            assertEquals(listOf("/name_vi"), client.api(e.CREATE_MENU_CATEGORY, a.managerToken, body = "{}").errorFields())
            for ((body, fields) in mapOf("{}" to listOf(""), """{"name_vi": "  "}""" to listOf("/name_vi"))) {
                val edit =
                    client.api(
                        e.UPDATE_MENU_CATEGORY_POST,
                        a.managerToken,
                        "categoryId" to rice,
                        body = body,
                        configure = ifMatch(0),
                    )
                assertEquals(HttpStatusCode.BadRequest, edit.status, body)
                assertEquals(fields, edit.errorFields(), body)
            }
            assertEquals(listOf("menu.category.created"), menuAudit(a.id))
        }

    @Test
    fun `IT-MENU-06 an edit without If-Match is 428 and a stale one 412`() =
        menuApp {
            val a = Tenants.create("A", "manager@a.example")
            val cat = client.category(a.managerToken, "Phở")
            val item = client.item(a.managerToken, cat, "Phở bò").str("id")
            val patch = """{"price": {"amount": "70000", "currency": "VND"}}"""

            assertEquals(
                "precondition_required",
                client.api(e.UPDATE_MENU_ITEM_POST, a.managerToken, "itemId" to item, body = patch).problem(),
            )
            assertEquals(
                "precondition_failed",
                client.api(e.UPDATE_MENU_ITEM_POST, a.managerToken, "itemId" to item, body = patch, configure = ifMatch(7)).problem(),
            )
            val ok = client.api(e.UPDATE_MENU_ITEM_POST, a.managerToken, "itemId" to item, body = patch, configure = ifMatch(0))
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals("\"1\"", ok.headers[HttpHeaders.ETag])
            assertEquals("70000", ok.json().obj("price").str("amount"))
            assertEquals(
                "precondition_failed",
                client.api(e.DELETE_MENU_ITEM, a.managerToken, "itemId" to item, configure = ifMatch(0)).problem(),
            )
            assertEquals("precondition_required", client.api(e.DELETE_MENU_CATEGORY, a.managerToken, "categoryId" to cat).problem())
            val catPatch =
                client.api(
                    e.UPDATE_MENU_CATEGORY_POST,
                    a.managerToken,
                    "categoryId" to cat,
                    body = """{"active": false}""",
                    configure = ifMatch(3),
                )
            assertEquals("precondition_failed", catPatch.problem())
            assertEquals(
                HttpStatusCode.NoContent,
                client.api(e.DELETE_MENU_ITEM, a.managerToken, "itemId" to item, configure = ifMatch(1)).status,
            )
        }

    @Test
    fun `IT-MENU-07 waiters and kitchen can't edit, and only kitchen and managers toggle sold out`() =
        menuApp {
            val a = Tenants.create("A", "manager@a.example")
            val (_, waiter) = a.member("waiter@a.example", "waiter")
            val (_, kitchen) = a.member("kitchen@a.example", "kitchen")
            val cat = client.category(a.managerToken, "Phở")
            val item = client.item(a.managerToken, cat, "Phở bò").str("id")

            for (token in listOf(waiter, kitchen)) {
                assertEquals("forbidden", client.newCategory(token, "Cơm").problem())
                assertEquals("forbidden", client.newItem(token, cat, "Bún").problem())
                assertEquals("forbidden", client.api(e.CREATE_MENU_ITEM, token, body = """{"price": 1}""").problem(), "role before input")
                assertEquals("forbidden", client.api(e.UPDATE_MENU_ITEM_POST, token, "itemId" to item, body = "{}").problem())
                assertEquals("forbidden", client.api(e.REORDER_MENU_CATEGORIES, token, body = "{}").problem())
                assertEquals(HttpStatusCode.OK, client.api(e.GET_STAFF_MENU, token).status, "every role reads the menu")
                assertEquals(HttpStatusCode.OK, client.api(e.GET_MENU_ITEM, token, "itemId" to item).status)
            }
            val sold = client.api(e.SET_MENU_ITEM_SOLD_OUT, kitchen, "itemId" to item, body = """{"sold_out": true}""")
            assertEquals(HttpStatusCode.OK, sold.status)
            assertEquals(item, sold.json().str("item_id"))
            assertEquals("true", sold.json().str("sold_out"))
            assertEquals("forbidden", client.api(e.SET_MENU_ITEM_SOLD_OUT, waiter, "itemId" to item, body = "nonsense").problem())
            assertEquals(listOf("/sold_out"), client.api(e.SET_MENU_ITEM_SOLD_OUT, kitchen, "itemId" to item, body = "{}").errorFields())
        }

    @Test
    fun `IT-MENU-08 another restaurant's IDs are 404 on every menu route, and leave it unchanged`() =
        menuApp {
            val a = Tenants.create("A", "manager@a.example")
            val b = Tenants.create("B", "manager@b.example")
            val catB = client.category(b.managerToken, "Phở")
            val itemB = client.item(b.managerToken, catB, "Phở bò", extra = optionGroup("Size", "Lớn")).str("id")
            val groupB =
                client
                    .api(
                        e.GET_MENU_ITEM,
                        b.managerToken,
                        "itemId" to itemB,
                    ).json()
                    .array("option_groups")
                    .objects()
                    .single()
                    .str("id")
            val catA = client.category(a.managerToken, "Cơm")
            val itemA = client.item(a.managerToken, catA, "Cơm tấm").str("id")
            val versionB = menuVersion(b.id)

            val t = a.managerToken
            val calls =
                listOf(
                    client.api(
                        e.UPDATE_MENU_CATEGORY_POST,
                        t,
                        "categoryId" to catB,
                        body = """{"active": false}""",
                        configure = ifMatch(0),
                    ),
                    client.api(e.DELETE_MENU_CATEGORY, t, "categoryId" to catB, configure = ifMatch(0)),
                    client.api(e.REORDER_MENU_ITEMS, t, "categoryId" to catB, body = """{"item_ids": ["$itemB"]}"""),
                    client.newItem(t, catB, "Bún"),
                    client.api(e.GET_MENU_ITEM, t, "itemId" to itemB),
                    client.api(e.UPDATE_MENU_ITEM_POST, t, "itemId" to itemB, body = """{"active": false}""", configure = ifMatch(0)),
                    client.api(
                        e.UPDATE_MENU_ITEM_POST,
                        t,
                        "itemId" to itemA,
                        body = """{"category_id": "$catB"}""",
                        configure = ifMatch(0),
                    ),
                    client.api(e.UPDATE_MENU_ITEM_POST, t, "itemId" to itemA, body = optionGroupsWithId(groupB), configure = ifMatch(0)),
                    client.api(e.DELETE_MENU_ITEM, t, "itemId" to itemB, configure = ifMatch(0)),
                    client.api(e.SET_MENU_ITEM_SOLD_OUT, t, "itemId" to itemB, body = """{"sold_out": true}"""),
                    client.api(e.GET_MENU_ITEM, t, "itemId" to "not-a-uuid"),
                )
            calls.forEachIndexed { i, response -> assertEquals("not_found", response.problem(), "call $i") }
            assertEquals(versionB, menuVersion(b.id), "B's menu is unchanged")
            val readB = client.api(e.GET_MENU_ITEM, b.managerToken, "itemId" to itemB).json()
            assertEquals(listOf("true", "false", "0"), listOf(readB.str("active"), readB.str("sold_out"), readB.str("version")))
        }

    @Test
    fun `IT-MENU-09 sold out twice bumps the menu once, and an edit opened before it still saves`() =
        menuApp {
            val a = Tenants.create("A", "manager@a.example")
            val (_, kitchen) = a.member("kitchen@a.example", "kitchen")
            val cat = client.category(a.managerToken, "Phở")
            val item = client.item(a.managerToken, cat, "Phở bò").str("id")
            val etag = client.api(e.GET_MENU_ITEM, a.managerToken, "itemId" to item).headers[HttpHeaders.ETag]!!
            val before = menuVersion(a.id)

            repeat(2) {
                val r = client.api(e.SET_MENU_ITEM_SOLD_OUT, kitchen, "itemId" to item, body = """{"sold_out": true}""")
                assertEquals(HttpStatusCode.OK, r.status)
            }
            assertEquals(before + 1, menuVersion(a.id), "the second toggle changed nothing")
            assertEquals(1, menuAudit(a.id).count { it == "menu.item.sold_out_changed" })

            val edit =
                client.api(e.UPDATE_MENU_ITEM_POST, a.managerToken, "itemId" to item, body = """{"name_en": "Beef pho"}""") {
                    header(HttpHeaders.IfMatch, etag)
                }
            assertEquals(HttpStatusCode.OK, edit.status, "sold out doesn't change the item's version")
            assertEquals("true", edit.json().str("sold_out"))
            client.api(e.SET_MENU_ITEM_SOLD_OUT, kitchen, "itemId" to item, body = """{"sold_out": false}""")
            assertEquals(before + 3, menuVersion(a.id))
        }

    @Test
    fun `IT-MENU-11 a category with items can't be deleted, and a reorder must name each one once`() =
        menuApp {
            val a = Tenants.create("A", "manager@a.example")
            val c1 = client.category(a.managerToken, "Một")
            val c2 = client.category(a.managerToken, "Hai")
            val c3 = client.category(a.managerToken, "Ba")
            val i1 = client.item(a.managerToken, c1, "A").str("id")
            val i2 = client.item(a.managerToken, c1, "B").str("id")

            assertEquals(
                "category_not_empty",
                client.api(e.DELETE_MENU_CATEGORY, a.managerToken, "categoryId" to c1, configure = ifMatch(0)).problem(),
            )
            val reorder = { ids: List<String> -> """{"category_ids": [${ids.joinToString { "\"$it\"" }}]}""" }
            assertEquals(
                listOf("/category_ids"),
                client.api(e.REORDER_MENU_CATEGORIES, a.managerToken, body = reorder(listOf(c3, c1))).errorFields(),
            )
            assertEquals(
                listOf("/category_ids"),
                client.api(e.REORDER_MENU_CATEGORIES, a.managerToken, body = reorder(listOf(c3, c1, c1))).errorFields(),
            )
            assertEquals(
                listOf("/category_ids"),
                client.api(e.REORDER_MENU_CATEGORIES, a.managerToken, body = reorder(listOf(c3, c1, c2, c1))).errorFields(),
            )
            assertEquals(
                HttpStatusCode.NoContent,
                client.api(e.REORDER_MENU_CATEGORIES, a.managerToken, body = reorder(listOf(c3, c1, c2))).status,
            )
            val items = """{"item_ids": ["$i2", "$i1"]}"""
            assertEquals(
                HttpStatusCode.NoContent,
                client.api(e.REORDER_MENU_ITEMS, a.managerToken, "categoryId" to c1, body = items).status,
            )
            assertEquals(
                listOf("/item_ids"),
                client.api(e.REORDER_MENU_ITEMS, a.managerToken, "categoryId" to c1, body = """{"item_ids": ["$i2"]}""").errorFields(),
            )

            val menu =
                client
                    .api(e.GET_STAFF_MENU, a.managerToken)
                    .json()
                    .array("categories")
                    .objects()
            assertEquals(listOf(c3, c1, c2), menu.map { it.str("id") })
            assertEquals(listOf(i2, i1), menu[1].array("items").objects().map { it.str("id") })
            assertEquals(listOf(0, 0, 0), menu.map { it.str("version").toInt() }, "a reorder doesn't change versions")

            assertEquals(
                HttpStatusCode.NoContent,
                client.api(e.DELETE_MENU_CATEGORY, a.managerToken, "categoryId" to c3, configure = ifMatch(0)).status,
            )
            assertEquals(HttpStatusCode.Created, client.newCategory(a.managerToken, "Ba").status, "a deleted category's name is free again")
        }

    @Test
    fun `IT-MENU-12 past a size limit is 409 menu_limit_reached`() =
        menuApp {
            val a = Tenants.create("A", "manager@a.example")
            val first = client.category(a.managerToken, "C0")
            (1 until 50).forEach { client.category(a.managerToken, "C$it") }
            assertEquals("menu_limit_reached", client.newCategory(a.managerToken, "C50").problem())

            assertEquals("menu_limit_reached", client.newItem(a.managerToken, first, "Big", extra = groups(11, 1)).problem(), "11 groups")
            assertEquals("menu_limit_reached", client.newItem(a.managerToken, first, "Big", extra = groups(1, 21)).problem(), "21 options")
            assertEquals(HttpStatusCode.Created, client.newItem(a.managerToken, first, "Big", extra = groups(10, 20)).status, "200 options")

            // 4,800 more options on one item, written directly: the restaurant is then at 5,000.
            seedOptions(a.id.value, first, 240)
            assertEquals("menu_limit_reached", client.newItem(a.managerToken, first, "One more", extra = groups(1, 1)).problem())
            assertEquals(HttpStatusCode.Created, client.newItem(a.managerToken, first, "No options").status)

            seedItems(a.id.value, first, 1000 - 3)
            assertEquals("menu_limit_reached", client.newItem(a.managerToken, first, "Item 1001").problem())
        }

    @Test
    fun `menu writes of one restaurant take turns, so two creates can't both pass the last free slot`() =
        menuApp {
            val a = Tenants.create("A", "manager@a.example")
            AuthFixtures.sql(
                "INSERT INTO menu_categories (id, restaurant_id, name_vi, display_order, created_at, updated_at) " +
                    "SELECT gen_random_uuid(), ?, 'C' || n, n, now(), now() FROM generate_series(1, 49) n",
                a.id.value,
            )
            repeat(ROUNDS) { round ->
                val statuses =
                    coroutineScope {
                        (1..2).map { n -> async { client.newCategory(a.managerToken, "Race $round-$n").status.value } }.map { it.await() }
                    }
                assertEquals(listOf(201, 409), statuses.sorted(), "round $round")
                val last =
                    AuthFixtures.query(
                        "SELECT id FROM menu_categories WHERE name_vi LIKE 'Race%' AND deleted_at IS NULL",
                    ) { it.getString(1) }
                AuthFixtures.sql("UPDATE menu_categories SET deleted_at = now() WHERE id = ?::uuid", last.single())
            }
            assertEquals(
                50,
                AuthFixtures.query("SELECT count(*) FROM menu_categories WHERE deleted_at IS NULL") { it.getInt(1) }.single() + 1,
            )
        }

    @Test
    fun `IT-MENU-15every menu write records its audit entry and a MenuChanged event, and a refused one neither`() =
        menuApp {
            val a = Tenants.create("A", "manager@a.example")
            val cat = client.category(a.managerToken, "Phở")
            val item = client.item(a.managerToken, cat, "Phở bò", extra = optionGroup("Size", "Lớn")).str("id")
            client.api(
                e.UPDATE_MENU_ITEM_POST,
                a.managerToken,
                "itemId" to item,
                body = """{"price": {"amount": "70000", "currency": "VND"}}""",
                configure = ifMatch(0),
            )
            client.api(
                e.UPDATE_MENU_CATEGORY_POST,
                a.managerToken,
                "categoryId" to cat,
                body = """{"name_en": "Noodles"}""",
                configure = ifMatch(0),
            )
            client.api(e.REORDER_MENU_ITEMS, a.managerToken, "categoryId" to cat, body = """{"item_ids": ["$item"]}""")
            client.api(e.SET_MENU_ITEM_SOLD_OUT, a.managerToken, "itemId" to item, body = """{"sold_out": true}""")
            client.api(e.DELETE_MENU_ITEM, a.managerToken, "itemId" to item, configure = ifMatch(1))
            assertEquals("duplicate_name", client.newCategory(a.managerToken, "phở").problem())

            val actions =
                listOf(
                    "menu.category.created",
                    "menu.item.created",
                    "menu.item.updated",
                    "menu.category.updated",
                    "menu.reordered",
                    "menu.item.sold_out_changed",
                    "menu.item.deleted",
                )
            assertEquals(actions, menuAudit(a.id))
            assertEquals(actions.size.toLong(), menuVersion(a.id))
            val events =
                AuthFixtures.query(
                    "SELECT e.type, e.aggregate_type, e.aggregate_id, e.aggregate_version, e.audience, o.published_at IS NOT NULL " +
                        "FROM domain_events e JOIN outbox o ON o.event_id = e.id WHERE e.restaurant_id = ? ORDER BY e.seq",
                    a.id.value,
                ) { rs ->
                    listOf(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4).toString(),
                        rs.getString(5),
                        rs.getBoolean(6).toString(),
                    )
                }
            // The last column: published to Ably after commit (realtime §4.2).
            assertEquals((1..actions.size).map { listOf("MenuChanged", "menu", a.id.toString(), "$it", "staff", "true") }, events)

            val priceEdit =
                AuthFixtures.query("SELECT details::text FROM audit_events WHERE action = 'menu.item.updated'") { it.getString(1) }.single()
            assertTrue(""""price": {"to": "70000", "from": "65000"}""" in priceEdit, priceEdit)
            assertTrue(""""fields": ["price"]""" in priceEdit, priceEdit)
        }

    private fun optionGroup(
        name: String,
        option: String,
    ) = """, "option_groups": [{"name_vi": "$name", "min_choices": 1, "max_choices": 1, "options": [{"name_vi": "$option"}]}]"""

    private fun optionGroupsWithId(groupId: String) =
        """{"option_groups": [{"id": "$groupId", "name_vi": "S", "min_choices": 0, "max_choices": 1, "options": [{"name_vi": "L"}]}]}"""

    private fun groups(
        count: Int,
        options: Int,
    ): String {
        val list = (1..options).joinToString { """{"name_vi": "O$it"}""" }
        return """, "option_groups": [${(1..count).joinToString {
            """{"name_vi": "G$it", "min_choices": 0, "max_choices": 1, "options": [$list]}"""
        }}]"""
    }

    /** One item with [groupCount] groups of 20 options each, written as the superuser. */
    private fun seedOptions(
        restaurant: java.util.UUID,
        category: String,
        groupCount: Int,
    ) = AuthFixtures.sql(
        """
        WITH item AS (
          INSERT INTO menu_items (id, restaurant_id, category_id, name_vi, price, display_order, created_at, updated_at)
          VALUES (gen_random_uuid(), ?, ?::uuid, 'Seeded', 1, 999, now(), now()) RETURNING id),
        grp AS (
          INSERT INTO menu_option_groups (id, restaurant_id, item_id, name_vi, min_choices, max_choices, display_order)
          SELECT gen_random_uuid(), ?, item.id, 'G' || g, 0, 1, g FROM item, generate_series(1, ?) g RETURNING id)
        INSERT INTO menu_options (id, restaurant_id, group_id, name_vi, display_order)
        SELECT gen_random_uuid(), ?, grp.id, 'O' || o, o FROM grp, generate_series(1, 20) o
        """.trimIndent(),
        restaurant,
        category,
        restaurant,
        groupCount,
        restaurant,
    )

    private fun seedItems(
        restaurant: java.util.UUID,
        category: String,
        count: Int,
    ) = AuthFixtures.sql(
        "INSERT INTO menu_items (id, restaurant_id, category_id, name_vi, price, display_order, created_at, updated_at) " +
            "SELECT gen_random_uuid(), ?, ?::uuid, 'Seeded ' || n, 1, 1000 + n, now(), now() FROM generate_series(1, ?) n",
        restaurant,
        category,
        count,
    )
}
