package io.github.phongmanh.restaurant.orders

import io.github.phongmanh.restaurant.core.application.RateLimiter
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.IDEMPOTENCY_KEY_HEADER
import io.github.phongmanh.restaurant.menu.array
import io.github.phongmanh.restaurant.menu.objects
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.errorFields
import io.github.phongmanh.restaurant.support.items
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.obj
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.github.phongmanh.restaurant.tables.auditOf
import io.github.phongmanh.restaurant.tables.count
import io.github.phongmanh.restaurant.tables.eventsOf
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IT-ORD-01 to IT-ORD-05, IT-ORD-18, IT-ORD-19 and IT-MENU-10's order half (orders §3, menu §3;
 * FR-003, FR-004, FR-007, FR-014, FR-020).
 */
class PlaceOrderTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val e = Endpoints.Orders

    @Test
    fun `IT-ORD-01 an order is priced from the database, kept as a snapshot, and shows on the bill and in the staff feed`() =
        ordersApp {
            val f = client.floor()
            // A price sent by the client is ignored like any unknown field.
            val sent =
                """{"lines": [{"item_id": "${f.pho}", "quantity": 2, "option_ids": ["${f.large}"], "note": "ít cay",
                   "unit_price": {"amount": "1", "currency": "VND"}}, ${line(f.tea)}], "total": {"amount": "1", "currency": "VND"}}"""
            val response = client.guestOrder(f.guest, sent)
            assertEquals(HttpStatusCode.Created, response.status)
            val order = response.json()
            assertEquals(e.GET_GUEST_ORDER.pathOf("orderId" to order.str("id")), response.headers[HttpHeaders.Location])
            assertEquals("\"0\"", response.headers[HttpHeaders.ETag])
            assertEquals(listOf("placed", "guest", "0"), listOf(order.str("status"), order.str("placed_by"), order.str("version")))
            assertEquals(f.seat.table.toString(), order.obj("table").str("id"))
            val pho = order.getValue("lines").jsonArray[0].jsonObject
            assertEquals(
                listOf("Phở bò", "Beef pho", "65000", "75000", "150000", "ít cay", "active"),
                listOf(
                    pho.str("name_vi"),
                    pho.str("name_en"),
                    pho.obj("unit_price").str("amount"),
                    pho.obj("unit_total").str("amount"),
                    pho.obj("line_total").str("amount"),
                    pho.str("note"),
                    pho.str("status"),
                ),
            )
            val option = pho.array("options").objects().single()
            assertEquals(
                listOf("Cỡ", "Lớn", "10000"),
                listOf(option.str("group_name_vi"), option.str("name_vi"), option.obj("price_delta").str("amount")),
            )
            assertEquals(155_000, order.money("total"))

            val bill = client.api(e.GET_GUEST_BILL, f.guest).json()
            assertEquals(listOf("open", "1"), listOf(bill.str("status"), bill.str("order_count")))
            assertEquals(155_000, bill.money("total"))
            assertEquals(2, bill.getValue("lines").jsonArray.size)

            val feed = client.api(e.LIST_STAFF_ORDERS, f.kitchen).items()
            assertEquals(listOf(order.str("id")), feed.map { it.jsonObject.str("id") })
            assertEquals(listOf("OrderPlaced"), eventsOf(f.id).filter { it.startsWith("Order") })
        }

    @Test
    fun `IT-ORD-02 every broken rule is listed with its pointer, and nothing is stored`() =
        ordersApp {
            val f = client.floor()
            val none = client.guestOrder(f.guest, """{"lines": []}""")
            assertEquals(listOf("/lines"), none.errorFields())
            val tooMany = client.guestOrder(f.guest, orderBody(*Array(51) { line(f.tea) }))
            assertEquals(listOf("/lines"), tooMany.errorFields())
            val unknown = UuidV7.next().toString()
            val bad =
                client.guestOrder(
                    f.guest,
                    orderBody(line(f.tea, quantity = 0), line(f.tea, quantity = 21), line(f.tea, note = "x".repeat(201)), line(unknown)),
                )
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertEquals(listOf("/lines/0/quantity", "/lines/1/quantity", "/lines/2/note"), bad.errorFields(), "form first")
            val notOnMenu = client.guestOrder(f.guest, orderBody(line(f.tea), line(unknown), line(f.pho)))
            assertEquals("validation_failed", notOnMenu.problem())
            assertEquals(
                listOf("unknown_item", "option_required"),
                notOnMenu
                    .json()
                    .getValue("errors")
                    .jsonArray
                    .map { it.jsonObject.str("code") },
            )
            assertEquals(listOf("/lines/1/item_id", "/lines/2/option_ids"), notOnMenu.errorFields())
            assertEquals(0, count("SELECT count(*) FROM orders"))
            assertEquals(0, count("SELECT count(*) FROM bills"))
        }

    @Test
    fun `IT-ORD-03 a sold-out and a hidden item in one order are both listed, and nothing is ordered`() =
        ordersApp {
            val f = client.floor()
            client.api(Endpoints.Menu.SET_MENU_ITEM_SOLD_OUT, f.kitchen, "itemId" to f.tea, body = """{"sold_out": true}""")
            client.api(Endpoints.Menu.UPDATE_MENU_ITEM, f.manager, "itemId" to f.pho, body = """{"active": false}""") {
                header(HttpHeaders.IfMatch, "\"0\"")
            }
            val response = client.guestOrder(f.guest, orderBody(line(f.pho, options = listOf(f.small)), line(f.tea)))
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("item_unavailable", response.problem())
            assertEquals(
                setOf(f.pho, f.tea),
                response
                    .json()
                    .getValue("unavailable_items")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
                    .toSet(),
            )
            assertEquals(0, count("SELECT count(*) FROM orders"))
        }

    @Test
    fun `IT-ORD-04 one Idempotency-Key makes one order, replayed, another body is refused, two at once make one`() =
        ordersApp {
            val f = client.floor()
            val key = UuidV7.next().toString()
            val body = orderBody(line(f.tea, 2))
            val first = client.guestOrder(f.guest, body, key)
            val replay = client.guestOrder(f.guest, body, key)
            assertEquals(HttpStatusCode.Created, replay.status)
            assertEquals("true", replay.headers["Idempotent-Replayed"])
            assertEquals(first.json(), replay.json())
            assertEquals(first.headers[HttpHeaders.Location], replay.headers[HttpHeaders.Location])
            assertEquals("idempotency_key_reused", client.guestOrder(f.guest, orderBody(line(f.tea, 3)), key).problem())
            assertEquals(422, client.guestOrder(f.guest, orderBody(line(f.tea, 3)), key).status.value)
            val noKey = client.api(e.PLACE_GUEST_ORDER, f.guest, body = body)
            assertEquals("idempotency_key_required", noKey.problem())

            val raced = UuidV7.next().toString()
            val answers = coroutineScope { (1..2).map { async { client.guestOrder(f.guest, body, raced) } }.awaitAll() }
            assertTrue(answers.all { it.status.value == 201 || it.problem() == "idempotency_request_in_progress" })
            assertEquals(2, count("SELECT count(*) FROM orders"), "one for each key")
            assertEquals(2, client.bill(f.waiter, f.session).int("order_count"))
        }

    @Test
    fun `IT-ORD-05 a second round goes on the same bill, and a later menu price change touches neither`() =
        ordersApp {
            val f = client.floor()
            val first = client.placed(f.guest, line(f.tea))
            val second = client.placed(f.guest, line(f.pho, options = listOf(f.small)))
            client.api(
                Endpoints.Menu.UPDATE_MENU_ITEM,
                f.manager,
                "itemId" to f.tea,
                body = """{"price": {"amount": "9000", "currency": "VND"}}""",
            ) {
                header(HttpHeaders.IfMatch, "\"0\"")
            }
            val bill = client.bill(f.waiter, f.session)
            assertEquals(70_000, bill.money("total"))
            assertEquals(2, bill.int("order_count"))
            val again = client.api(e.GET_GUEST_ORDER, f.guest, "orderId" to first.str("id")).json()
            assertEquals(first, again, "the first round is unchanged")
            val orders = client.api(e.LIST_GUEST_ORDERS, f.guest).items().map { it.jsonObject.str("id") }
            assertEquals(listOf(second.str("id"), first.str("id")), orders, "newest first")
        }

    @Test
    fun `IT-ORD-05 the guest's orders page with a cursor`() =
        ordersApp {
            val f = client.floor()
            val ids = (1..3).map { client.placed(f.guest, line(f.tea)).str("id") }
            val first = client.api(e.LIST_GUEST_ORDERS.method, e.LIST_GUEST_ORDERS.path + "?limit=2", f.guest).json()
            val cursor = first.str("next_cursor")
            val rest = client.api(e.LIST_GUEST_ORDERS.method, e.LIST_GUEST_ORDERS.path + "?limit=2&cursor=$cursor", f.guest).json()
            assertEquals(
                ids.reversed(),
                (first.getValue("items").jsonArray + rest.getValue("items").jsonArray).map { it.jsonObject.str("id") },
            )
            assertNull(rest["next_cursor"])
        }

    @Test
    fun `a malformed list query or staff order is 400, and nothing is ordered`() =
        ordersApp {
            val f = client.floor()
            for (query in listOf("?limit=0", "?cursor=nope")) {
                val guest = client.api(e.LIST_GUEST_ORDERS.method, e.LIST_GUEST_ORDERS.path + query, f.guest)
                assertEquals(HttpStatusCode.BadRequest, guest.status, query)
                assertEquals("validation_failed", guest.problem(), query)
            }
            for (query in listOf("?status=eaten", "?session_id=nope", "?limit=201")) {
                val feed = client.api(e.LIST_STAFF_ORDERS.method, e.LIST_STAFF_ORDERS.path + query, f.waiter)
                assertEquals(HttpStatusCode.BadRequest, feed.status, query)
                assertEquals("validation_failed", feed.problem(), query)
            }
            val empty =
                client.api(e.PLACE_STAFF_ORDER, f.waiter, "sessionId" to f.session, body = orderBody()) {
                    header(IDEMPOTENCY_KEY_HEADER, UuidV7.next().toString())
                }
            assertEquals(HttpStatusCode.BadRequest, empty.status)
            assertEquals(listOf("/lines"), empty.errorFields())
            assertEquals(0, count("SELECT count(*) FROM orders"))
        }

    @Test
    fun `IT-ORD-18 staff order for a table, audited, and the kitchen may not place one`() =
        ordersApp {
            val f = client.floor()
            val path = "sessionId" to f.session
            val response =
                client.api(e.PLACE_STAFF_ORDER, f.waiter, path, body = orderBody(line(f.tea))) {
                    header(IDEMPOTENCY_KEY_HEADER, UuidV7.next().toString())
                }
            assertEquals(HttpStatusCode.Created, response.status)
            val order = response.json()
            assertEquals(listOf("staff", f.waiterId.toString()), listOf(order.str("placed_by"), order.str("placed_by_staff_id")))
            assertEquals(e.GET_STAFF_ORDER.pathOf("orderId" to order.str("id")), response.headers[HttpHeaders.Location])
            assertEquals(listOf("order.placed_by_staff"), auditOf(f.id, "order."))
            val guestView = client.api(e.GET_GUEST_ORDER, f.guest, "orderId" to order.str("id")).json()
            assertEquals("staff", guestView.str("placed_by"))
            assertNull(guestView["placed_by_staff_id"], "staff only")

            val kitchen =
                client.api(e.PLACE_STAFF_ORDER, f.kitchen, path, body = orderBody(line(f.tea))) {
                    header(IDEMPOTENCY_KEY_HEADER, UuidV7.next().toString())
                }
            assertEquals("forbidden", kitchen.problem())
            assertEquals(1, count("SELECT count(*) FROM orders"))
        }

    @Test
    fun `IT-ORD-19 the 11th order in a minute from one session is refused with Retry-After`() {
        val counts = ConcurrentHashMap<String, Long>()
        val counting = RateLimiter { key, _ -> counts.merge(key.substringBeforeLast(':'), 1, Long::plus)!! }
        ordersApp(limiter = counting) {
            val f = client.floor()
            repeat(10) { client.placed(f.guest, line(f.tea)) }
            val eleventh = client.guestOrder(f.guest, orderBody(line(f.tea)))
            assertEquals(HttpStatusCode.TooManyRequests, eleventh.status)
            assertTrue(eleventh.headers[HttpHeaders.RetryAfter]!!.toLong() in 1..60)
            assertEquals(10, count("SELECT count(*) FROM orders"))
        }
    }

    @Test
    fun `IT-MENU-10 an ordered item deleted from the menu keeps its snapshot on the order and the bill`() =
        ordersApp {
            val f = client.floor()
            val order = client.placed(f.guest, line(f.pho, options = listOf(f.large)), line(f.tea))
            val version = count("SELECT version FROM menu_items WHERE id = ?::uuid", f.pho)
            val deleted =
                client.api(Endpoints.Menu.DELETE_MENU_ITEM, f.manager, "itemId" to f.pho) { header(HttpHeaders.IfMatch, "\"$version\"") }
            assertEquals(HttpStatusCode.NoContent, deleted.status)

            val read = client.api(Endpoints.Orders.GET_STAFF_ORDER, f.waiter, "orderId" to order.str("id")).json()
            val pho = read.getValue("lines").jsonArray[0].jsonObject
            assertEquals(
                listOf(f.pho, "Phở bò", "75000"),
                listOf(pho.str("item_id"), pho.str("name_vi"), pho.obj("line_total").str("amount")),
            )
            val bill = client.bill(f.waiter, f.session)
            assertEquals(listOf("Phở bò", "Trà đá"), bill.getValue("lines").jsonArray.map { it.jsonObject.str("name_vi") })
            assertEquals("80000", bill.obj("total").str("amount"))
        }
}
