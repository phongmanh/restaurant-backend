package io.github.phongmanh.restaurant.orders

import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.IDEMPOTENCY_KEY_HEADER
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Guests
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.TestKeys
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.items
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.obj
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.github.phongmanh.restaurant.tables.job
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** IT-ORD-06, IT-ORD-20 and IT-ORD-21 (FR-042, FR-043, NFR-SEC-02, NFR-PRIV-01), and the session's bill summary (orders §16). */
class OrderAccessTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val e = Endpoints.Orders

    /** Every staff route that names [order], its line [lineId] or [a]'s session, called by [b]'s manager. */
    private suspend fun HttpClient.staffCallsOn(
        b: Floor,
        a: Floor,
        order: String,
        lineId: String,
    ): List<HttpResponse> {
        val ifMatch: HttpRequestBuilder.() -> Unit = { header(HttpHeaders.IfMatch, "\"0\"") }
        return listOf(
            api(e.GET_STAFF_ORDER, b.manager, "orderId" to order),
            api(
                e.CHANGE_ORDER_STATUS,
                b.manager,
                "orderId" to order,
                body = """{"status": "accepted"}""",
                configure = ifMatch,
            ),
            api(
                e.REJECT_ORDER_LINE,
                b.manager,
                "orderId" to order,
                "lineId" to lineId,
                body = """{"reason": {"code": "other"}}""",
                configure = ifMatch,
            ),
            api(e.GET_SESSION_BILL, b.manager, "sessionId" to a.session),
            api(e.CLOSE_TABLE_SESSION, b.manager, "sessionId" to a.session, body = "{}") {
                header(HttpHeaders.IfMatch, "\"1\"")
                header(IDEMPOTENCY_KEY_HEADER, UuidV7.next().toString())
            },
            api(
                e.ADJUST_CLOSED_BILL,
                b.manager,
                "sessionId" to a.session,
                body = """{"amount": {"amount": "-1", "currency": "VND"}, "payment_method": "cash", "reason_code": "other"}""",
                configure = ifMatch,
            ),
            api(e.PLACE_STAFF_ORDER, b.manager, "sessionId" to a.session, body = orderBody(line(a.tea))) {
                header(IDEMPOTENCY_KEY_HEADER, UuidV7.next().toString())
            },
        )
    }

    @Test
    fun `IT-ORD-06 another session's or restaurant's order is 404 on every route, and stays unchanged`() =
        ordersApp {
            val a = client.floor("A")
            val b = client.floor("B")
            val order = client.placed(a.guest, line(a.tea)).str("id")
            val line =
                client
                    .api(
                        e.GET_STAFF_ORDER,
                        a.kitchen,
                        "orderId" to order,
                    ).json()
                    .getValue("lines")
                    .jsonArray[0]
                    .jsonObject
                    .str("id")
            val neighbour = Guests.seated(a.id)

            // Guests: another session of the same restaurant, and another restaurant's guest.
            for (guest in listOf(neighbour.token, b.guest)) {
                assertEquals("not_found", client.api(e.GET_GUEST_ORDER, guest, "orderId" to order).problem())
                assertEquals("not_found", client.api(e.CANCEL_GUEST_ORDER, guest, "orderId" to order).problem())
                assertEquals(0, client.api(e.LIST_GUEST_ORDERS, guest).items().size)
            }
            // Staff of restaurant B, on every staff route that names A's order or session.
            val calls = client.staffCallsOn(b, a, order, line)
            calls.forEach { assertEquals("not_found", it.problem(), it.call.request.url.encodedPath) }
            assertEquals(
                0,
                client.api(e.LIST_STAFF_ORDERS.method, e.LIST_STAFF_ORDERS.path + "?session_id=${a.session}", b.kitchen).items().size,
            )
            val unchanged = client.api(e.GET_STAFF_ORDER, a.kitchen, "orderId" to order).json()
            assertEquals(listOf("placed", "0"), listOf(unchanged.str("status"), unchanged.str("version")))
            assertEquals(listOf("open", "1"), client.bill(a.waiter, a.session).let { listOf(it.str("status"), it.str("order_count")) })
        }

    @Test
    fun `IT-ORD-20 a suspended restaurant's guests can read their orders and bill, but not order or cancel`() =
        ordersApp {
            val f = client.floor()
            val order = client.placed(f.guest, line(f.tea)).str("id")
            AuthFixtures.suspend(f.id)
            assertEquals("restaurant_suspended", client.guestOrder(f.guest, orderBody(line(f.tea))).problem())
            assertEquals("restaurant_suspended", client.api(e.CANCEL_GUEST_ORDER, f.guest, "orderId" to order).problem())
            assertEquals(HttpStatusCode.OK, client.api(e.LIST_GUEST_ORDERS, f.guest).status)
            assertEquals(HttpStatusCode.OK, client.api(e.GET_GUEST_ORDER, f.guest, "orderId" to order).status)
            assertEquals(HttpStatusCode.OK, client.api(e.GET_GUEST_BILL, f.guest).status)
            assertEquals(
                "restaurant_suspended",
                client.api(e.LIST_STAFF_ORDERS, f.kitchen).problem(),
                "staff only sign in and read reports",
            )
        }

    /** A closed bill of [old] with a guest note on each line, a line rejected and an order cancelled with staff notes. */
    private suspend fun HttpClient.closedWithNotes(old: Floor) {
        val order =
            placed(
                old.guest,
                line(old.tea, note = "không đá"),
                line(old.pho, options = listOf(old.small), note = "ít hành"),
            )
        val id = order.str("id")
        step(old.kitchen, id, "accepted", 0)
        val pho =
            order
                .getValue("lines")
                .jsonArray[1]
                .jsonObject
                .str("id")
        api(
            e.REJECT_ORDER_LINE,
            old.kitchen,
            "orderId" to id,
            "lineId" to pho,
            body = """{"reason": {"code": "other", "note": "hết hành"}}""",
        ) {
            header(HttpHeaders.IfMatch, "\"1\"")
        }
        val cancelled = placed(old.guest, line(old.tea)).str("id")
        step(old.waiter, cancelled, "cancelled", 0, """{"code": "customer_request", "note": "khách đổi ý"}""")
        listOf("preparing", "ready", "served").forEachIndexed { v, s -> step(old.kitchen, id, s, v + 2) }
        close(old.waiter, old.session, bill(old.waiter, old.session).int("version"), cash(5000))
    }

    @Test
    fun `IT-ORD-21 notes and reason notes are cleared 30 days after the close, and never reach the audit`() =
        ordersApp {
            val old = client.floor("A")
            client.closedWithNotes(old)

            val recent = client.floor("B")
            client.placed(recent.guest, line(recent.tea, note = "mang về"))
            val recentClosed = client.floor("C")
            client.placed(recentClosed.guest, line(recentClosed.tea, note = "thêm chanh")).str("id").let {
                listOf("accepted", "preparing", "ready", "served").forEachIndexed { v, s -> client.step(recentClosed.kitchen, it, s, v) }
            }
            client.close(
                recentClosed.waiter,
                recentClosed.session,
                client.bill(recentClosed.waiter, recentClosed.session).int("version"),
                cash(5000),
            )
            AuthFixtures.sql("UPDATE bills SET closed_at = closed_at - interval '31 days' WHERE session_id = ?", old.seat.session.value)
            AuthFixtures.sql(
                "UPDATE bills SET closed_at = closed_at - interval '29 days' WHERE session_id = ?",
                recentClosed.seat.session.value,
            )

            fun notes(): Set<String> =
                AuthFixtures
                    .query(
                        "SELECT coalesce(note, '') || '/' || coalesce(reason_note, '') FROM order_lines " +
                            "WHERE note IS NOT NULL OR reason_note IS NOT NULL " +
                            "UNION ALL SELECT '/' || reason_note FROM orders WHERE reason_note IS NOT NULL",
                    ) { it.getString(1) }
                    .toSet()
            assertEquals(setOf("/khách đổi ý", "không đá/", "mang về/", "thêm chanh/", "ít hành/hết hành"), notes())

            val run = client.job(Endpoints.Jobs.RETENTION, TestKeys.CRON_SECRET)
            assertEquals(HttpStatusCode.OK, run.status)
            assertEquals("3", run.json().obj("tasks").str("order_notes"))
            assertEquals(setOf("mang về/", "thêm chanh/"), notes(), "an open bill, and one closed 29 days ago, keep theirs")
            assertEquals(
                "0",
                client
                    .job(Endpoints.Jobs.RETENTION, TestKeys.CRON_SECRET)
                    .json()
                    .obj("tasks")
                    .str("order_notes"),
            )
            assertEquals("unauthenticated", client.job(Endpoints.Jobs.RETENTION, "wrong").problem())
            val texts = AuthFixtures.query("SELECT details::text FROM audit_events") { it.getString(1) }
            assertEquals(emptyList(), texts.filter { t -> listOf("đá", "hành", "đổi ý", "chanh").any { it in t } })
        }

    @Test
    fun `a staff session read carries the bill summary from orders`() =
        ordersApp {
            val f = client.floor()
            val path = "sessionId" to f.session
            val empty = client.api(Endpoints.Tables.GET_TABLE_SESSION, f.waiter, path).json().obj("bill")
            assertEquals(listOf("0", "0"), listOf(empty.str("order_count"), empty.obj("total").str("amount")))
            client.placed(f.guest, line(f.tea, 3))
            val bill = client.api(Endpoints.Tables.GET_TABLE_SESSION, f.waiter, path).json().obj("bill")
            assertEquals(listOf("1", "15000"), listOf(bill.str("order_count"), bill.obj("total").str("amount")))
            val floor =
                client
                    .api(Endpoints.Tables.LIST_TABLES, f.waiter)
                    .items()
                    .single()
                    .jsonObject
                    .obj("session")
            assertEquals(listOf("1", "15000"), listOf(floor.str("order_count"), floor.obj("running_total").str("amount")))
            assertNull(
                client.api(Endpoints.Tables.OPEN_SESSION_BY_STAFF, f.waiter, "tableId" to f.seat.table).json()["bill"],
                "only a read has it",
            )
        }
}
