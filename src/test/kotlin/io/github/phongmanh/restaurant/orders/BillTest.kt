package io.github.phongmanh.restaurant.orders

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.reports.application.ClosedBillSale
import io.github.phongmanh.restaurant.reports.application.SalesLedger
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Guests
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.TestKeys
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
import io.github.phongmanh.restaurant.tables.job
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** IT-ORD-12 to IT-ORD-17 and IT-ORD-22 (orders §4.1, §7; FR-013, TBD-7). */
class BillTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val e = Endpoints.Orders

    /** Moves [orderId] through the kitchen to `served`. */
    private suspend fun HttpClient.serve(
        f: Floor,
        orderId: String,
    ) = listOf("accepted", "preparing", "ready", "served").forEachIndexed { v, s ->
        assertEquals(200, step(f.kitchen, orderId, s, v).status.value, s)
    }

    @Test
    fun `IT-ORD-12 a close records the payment, closes the session, and a replay gives the same answer`() =
        ordersApp {
            val f = client.floor()
            val order = client.placed(f.guest, line(f.pho, 2, listOf(f.large)), line(f.tea))
            client.serve(f, order.str("id"))
            val bill = client.bill(f.waiter, f.session)
            assertEquals(155_000, bill.money("total"))
            val version = bill.int("version")
            val key = UuidV7.next().toString()
            val closed = client.close(f.waiter, f.session, version, cash(200_000), key)
            assertEquals(HttpStatusCode.OK, closed.status)
            val body = closed.json()
            assertEquals(
                listOf("closed", "cash", "200000", "45000"),
                listOf(
                    body.str("status"),
                    body.obj("payment").str("method"),
                    body.obj("payment").obj("amount_received").str("amount"),
                    body.obj("payment").obj("change").str("amount"),
                ),
            )
            assertEquals(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString(), body.str("business_date"))
            assertEquals("\"${version + 1}\"", closed.headers[HttpHeaders.ETag])
            assertEquals("table_session_closed", client.api(e.GET_GUEST_BILL, f.guest).problem(), "the guest's token stops working")
            assertEquals(
                "closed/paid",
                AuthFixtures
                    .query("SELECT status || '/' || close_reason FROM table_sessions WHERE id = ?", f.seat.session.value) {
                        it.getString(1)
                    }.single(),
            )

            val replay = client.close(f.waiter, f.session, version, cash(200_000), key)
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals("true", replay.headers["Idempotent-Replayed"])
            assertEquals(body, replay.json())
            assertEquals(
                "session_not_open",
                client.close(f.waiter, f.session, version + 1, cash(200_000)).problem(),
                "a new close of a closed session",
            )
            assertEquals(listOf("bill.closed"), auditOf(f.id, "bill."))
            assertEquals(listOf("BillClosed", "TableSessionClosed"), eventsOf(f.id).takeLast(2))
            assertEquals("forbidden", client.close(f.kitchen, f.session, version, cash(1)).problem())
        }

    @Test
    fun `IT-ORD-12 a session with nothing on the bill closes without a payment, and refuses one`() =
        ordersApp {
            val f = client.floor()
            val id = client.placed(f.guest, line(f.tea)).str("id")
            client.api(e.CANCEL_GUEST_ORDER, f.guest, "orderId" to id)
            val bill = client.bill(f.waiter, f.session)
            assertEquals(listOf(0L, 2L), listOf(bill.money("total"), bill.str("version").toLong()))
            assertEquals(listOf("/payment"), client.close(f.waiter, f.session, 2, cash(5000)).errorFields())
            val closed = client.close(f.waiter, f.session, 2, "{}")
            assertEquals(HttpStatusCode.OK, closed.status)
            assertNull(closed.json()["payment"])
            assertEquals(
                "closed_by_staff",
                AuthFixtures
                    .query(
                        "SELECT close_reason FROM table_sessions WHERE id = ?",
                        f.seat.session.value,
                    ) { it.getString(1) }
                    .single(),
            )
            assertEquals(0, count("SELECT count(*) FROM daily_sales"), "nothing sold")

            val empty = client.floor("B")
            val none = client.bill(empty.waiter, empty.session)
            assertEquals(listOf("open", "0", "0"), listOf(none.str("status"), none.str("version"), none.obj("total").str("amount")))
            assertEquals(HttpStatusCode.OK, client.close(empty.waiter, empty.session, 0, "{}").status, "nothing ordered: no bill row yet")
        }

    @Test
    fun `IT-ORD-13 unfinished orders stop a close, and with force they're cancelled and leave the bill`() =
        ordersApp {
            val f = client.floor()
            val served = client.placed(f.guest, line(f.tea)).str("id")
            client.serve(f, served)
            val cooking = client.placed(f.guest, line(f.pho, options = listOf(f.small))).str("id")
            client.step(f.kitchen, cooking, "accepted", 0)
            val waiting = client.placed(f.guest, line(f.tea, 3)).str("id")
            val version = client.bill(f.waiter, f.session).int("version")

            val refused = client.close(f.waiter, f.session, version, cash(100_000))
            assertEquals("orders_not_finished", refused.problem())
            assertEquals(
                setOf(cooking, waiting),
                refused
                    .json()
                    .getValue("order_ids")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
                    .toSet(),
            )

            val forced =
                client.close(
                    f.waiter,
                    f.session,
                    version,
                    """{"payment": {"method": "card", "amount_received": {"amount": "5000", "currency": "VND"}}, "force": true}""",
                )
            assertEquals(HttpStatusCode.OK, forced.status)
            val bill = forced.json()
            assertEquals(5_000, bill.money("total"))
            assertEquals(
                listOf(served),
                bill
                    .getValue("lines")
                    .jsonArray
                    .map { it.jsonObject.str("order_id") }
                    .distinct(),
            )
            listOf(cooking, waiting).forEach {
                val order = client.api(e.GET_STAFF_ORDER, f.waiter, "orderId" to it).json()
                assertEquals(listOf("cancelled", "closed_with_session"), listOf(order.str("status"), order.obj("reason").str("code")))
            }
            assertEquals(
                "2",
                AuthFixtures
                    .query("SELECT details->>'forced_cancellations' FROM audit_events WHERE action = 'bill.closed'") {
                        it.getString(1)
                    }.single(),
            )
        }

    @Test
    fun `IT-ORD-14 an order between the bill read and the close stops it, and the payment must cover the total`() =
        ordersApp {
            val f = client.floor()
            client.serve(f, client.placed(f.guest, line(f.tea)).str("id"))
            val read = client.bill(f.waiter, f.session).int("version")
            client.serve(f, client.placed(f.guest, line(f.tea)).str("id"))
            assertEquals(
                "precondition_failed",
                client.close(f.waiter, f.session, read, cash(5000)).problem(),
                "the waiter sees the new total",
            )
            val current = client.bill(f.waiter, f.session)
            assertEquals(10_000, current.money("total"))
            val short = client.close(f.waiter, f.session, current.int("version"), cash(9999))
            assertEquals("validation_failed", short.problem())
            assertEquals(
                listOf("/payment/amount_received" to "less_than_total"),
                short
                    .json()
                    .getValue("errors")
                    .jsonArray
                    .map { it.jsonObject.str("field") to it.jsonObject.str("code") },
            )
            assertEquals(listOf("/payment"), client.close(f.waiter, f.session, current.int("version"), "{}").errorFields())
            val noIfMatch =
                client.api(e.CLOSE_TABLE_SESSION, f.waiter, "sessionId" to f.session, body = cash(10_000)) {
                    header("Idempotency-Key", UuidV7.next().toString())
                }
            assertEquals("precondition_required", noIfMatch.problem())
            assertEquals("open", client.bill(f.waiter, f.session).str("status"))
        }

    @Test
    fun `IT-ORD-15 an order racing a close is either on the bill or refused, never in a closed session`() =
        ordersApp {
            repeat(5) { round ->
                val f = client.floor("R$round")
                client.serve(f, client.placed(f.guest, line(f.tea)).str("id"))
                val version = client.bill(f.waiter, f.session).int("version")
                val (order, close) =
                    coroutineScope {
                        listOf(
                            async { client.guestOrder(f.guest, orderBody(line(f.tea))) },
                            async { client.close(f.waiter, f.session, version, cash(5000)) },
                        ).awaitAll()
                    }
                if (close.status == HttpStatusCode.OK) {
                    assertTrue(order.status.value in setOf(401, 409), "after the close: ${order.status}")
                    assertEquals(1, count("SELECT count(*) FROM orders WHERE session_id = ?", f.seat.session.value))
                } else {
                    assertEquals(HttpStatusCode.Created, order.status)
                    assertEquals("precondition_failed", close.problem(), "the order moved the bill on")
                }
                val inClosed =
                    count(
                        "SELECT count(*) FROM orders o JOIN bills b ON b.session_id = o.session_id " +
                            "WHERE b.status = 'closed' AND o.placed_at > b.closed_at",
                    )
                assertEquals(0, inClosed)
            }
        }

    @Test
    fun `IT-ORD-16 a close adds to the daily totals on the local day of the close, in the same transaction`() {
        // A time zone where it's 00:30 now: a bill closed then counts on the new local day.
        val now = Instant.now()
        val offsetSeconds =
            Math.floorMod(30 * 60 - now.atOffset(ZoneOffset.UTC).toLocalTime().toSecondOfDay(), 86_400).let {
                if (it >
                    43_200
                ) {
                    it - 86_400
                } else {
                    it
                }
            }
        val zone = ZoneOffset.ofTotalSeconds(offsetSeconds / 60 * 60)
        val localDay = LocalDate.ofInstant(now, zone)
        ordersApp {
            val f = client.floor()
            AuthFixtures.sql("UPDATE restaurants SET time_zone = ? WHERE id = ?", zone.id, f.id.value)
            val order = client.placed(f.guest, line(f.pho, 2, listOf(f.large)), line(f.tea, 2))
            client.serve(f, order.str("id"))
            val closed = client.close(f.waiter, f.session, client.bill(f.waiter, f.session).int("version"), cash(160_000)).json()
            assertEquals(localDay.toString(), closed.str("business_date"))
            assertTrue(
                LocalDate.ofInstant(now.minus(40, ChronoUnit.MINUTES), zone).isBefore(localDay),
                "40 minutes earlier was the day before",
            )

            val second = client.floor("B")
            val guest2 = Guests.seated(f.id)
            client.serve(f, client.placed(guest2.token, line(f.tea)).str("id"))
            val version2 = client.bill(f.manager, guest2.session.toString()).int("version")
            val transfer = """{"payment": {"method": "bank_transfer", "amount_received": {"amount": "5000", "currency": "VND"}}}"""
            assertEquals(HttpStatusCode.OK, client.close(f.manager, guest2.session.toString(), version2, transfer).status)
            assertEquals(0, count("SELECT count(*) FROM daily_sales WHERE restaurant_id = ?", second.id.value), "another restaurant")

            assertEquals(
                listOf("sales_date=$localDay sessions=2 orders=2 gross=165000 corrections=0 correction_count=0"),
                dailySales(
                    f.id,
                    "SELECT sales_date, sessions, orders, gross, corrections, correction_count FROM daily_sales WHERE restaurant_id = ?",
                ),
            )
            assertEquals(
                listOf("method=bank_transfer sessions=1 amount=5000", "method=cash sessions=1 amount=160000"),
                dailySales(f.id, "SELECT method, sessions, amount FROM daily_sales_payment WHERE restaurant_id = ? ORDER BY method"),
            )
            assertEquals(
                listOf("name_vi=Phở bò quantity=2 revenue=150000", "name_vi=Trà đá quantity=3 revenue=15000"),
                dailySales(f.id, "SELECT name_vi, quantity, revenue FROM daily_sales_item WHERE restaurant_id = ? ORDER BY name_vi"),
            )
            assertEquals(2, count("SELECT count(*) FROM daily_sales_table WHERE restaurant_id = ?", f.id.value))
        }
    }

    @Test
    fun `IT-ORD-16 bills closing at the same moment queue on the day's rows instead of deadlocking`() =
        ordersApp {
            val f = client.floor()
            val seats = (1..6).map { Guests.seated(f.id) }
            seats.forEachIndexed { i, seat ->
                // Items in either order: the ledger sorts them, so every close locks the rows alike.
                val lines = listOf(line(f.pho, options = listOf(f.small)), line(f.tea)).let { if (i % 2 == 0) it else it.reversed() }
                client.serve(f, client.placed(seat.token, *lines.toTypedArray()).str("id"))
            }
            val versions = seats.map { client.bill(f.waiter, it.session.toString()).int("version") }
            val answers =
                coroutineScope {
                    seats
                        .mapIndexed {
                            i,
                            seat,
                            ->
                            async { client.close(f.waiter, seat.session.toString(), versions[i], cash(70_000)) }
                        }.awaitAll()
                }
            assertEquals(List(6) { HttpStatusCode.OK }, answers.map { it.status })
            assertEquals(
                listOf("sessions=6 orders=6 gross=420000"),
                dailySales(f.id, "SELECT sessions, orders, gross FROM daily_sales WHERE restaurant_id = ?"),
            )
            assertEquals(
                listOf("quantity=6 revenue=390000", "quantity=6 revenue=30000"),
                dailySales(f.id, "SELECT quantity, revenue FROM daily_sales_item WHERE restaurant_id = ? ORDER BY revenue DESC"),
            )
        }

    @Test
    fun `IT-ORD-16 a ledger failure rolls the close back`() {
        val failing =
            object : SalesLedger {
                override fun recordClosedBill(
                    tx: TxScope,
                    restaurantId: RestaurantId,
                    sale: ClosedBillSale,
                ) = error("ledger down")

                override fun recordCorrection(
                    tx: TxScope,
                    restaurantId: RestaurantId,
                    businessDate: LocalDate,
                    paymentMethod: String,
                    amount: Long,
                ) = error("ledger down")
            }
        ordersApp(overrides = listOf(module { single<SalesLedger> { failing } })) {
            val f = client.floor()
            client.serve(f, client.placed(f.guest, line(f.tea)).str("id"))
            val version = client.bill(f.waiter, f.session).int("version")
            assertEquals(HttpStatusCode.InternalServerError, client.close(f.waiter, f.session, version, cash(5000)).status)
            assertEquals("open", client.bill(f.waiter, f.session).str("status"))
            assertEquals(HttpStatusCode.OK, client.api(e.GET_GUEST_BILL, f.guest).status, "the session is still open")
        }
    }

    @Test
    fun `IT-ORD-17 a manager corrects a closed bill on the day it's made, and a method change is two entries`() =
        ordersApp {
            val f = client.floor()
            client.serve(f, client.placed(f.guest, line(f.pho, options = listOf(f.small)), line(f.tea)).str("id"))
            val open = client.bill(f.manager, f.session)

            suspend fun adjust(
                token: String,
                version: Int,
                amount: Long,
                method: String = "cash",
                reason: String = "wrong_item_billed",
            ) = client.api(
                e.ADJUST_CLOSED_BILL,
                token,
                "sessionId" to f.session,
                body =
                    """{"amount": {"amount": "$amount", "currency": "VND"}, "payment_method": "$method",
                       "reason_code": "$reason", "note": "khách trả lại"}""",
            ) { header(HttpHeaders.IfMatch, "\"$version\"") }

            assertEquals("bill_not_closed", adjust(f.manager, open.int("version"), -5000).problem())
            val closed = client.close(f.waiter, f.session, open.int("version"), cash(70_000)).json()
            val v = closed.int("version")
            assertEquals("forbidden", adjust(f.waiter, v, -5000).problem())
            assertEquals(listOf("/amount"), adjust(f.manager, v, -70_001).errorFields(), "the corrected total can't go below 0")
            val first = adjust(f.manager, v, -5000)
            assertEquals(HttpStatusCode.Created, first.status)
            val corrected = first.json()
            assertEquals(listOf(70_000L, 65_000L), listOf(corrected.money("total"), corrected.money("adjusted_total")))
            assertEquals("precondition_failed", adjust(f.manager, v, -5000).problem(), "a repeat with the old If-Match")
            // The payment method was wrong: −X on the old one, +X on the new one.
            adjust(f.manager, v + 1, -65_000, "cash", "wrong_payment_method")
            val moved = adjust(f.manager, v + 2, 65_000, "card", "wrong_payment_method").json()
            assertEquals(3, moved.getValue("adjustments").jsonArray.size)
            assertEquals(65_000, moved.money("adjusted_total"))

            val today = closed.str("business_date")
            assertEquals(
                listOf("gross=70000 corrections=-5000 correction_count=3"),
                dailySales(f.id, "SELECT gross, corrections, correction_count FROM daily_sales WHERE restaurant_id = ?"),
            )
            assertEquals(
                listOf("method=card amount=0 corrections=65000", "method=cash amount=70000 corrections=-70000"),
                dailySales(f.id, "SELECT method, amount, corrections FROM daily_sales_payment WHERE restaurant_id = ? ORDER BY method"),
            )
            assertEquals(List(3) { "bill.adjusted" }, auditOf(f.id, "bill.adjusted"))
            assertEquals(0, count("SELECT count(*) FROM audit_events WHERE details::text LIKE '%khách%'"), "no note text in the audit")

            val list = client.api(e.LIST_CLOSED_BILLS.method, e.LIST_CLOSED_BILLS.path + "?date=$today", f.waiter).items()
            assertEquals(listOf(f.session), list.map { it.jsonObject.str("session_id") })
            assertEquals(65_000, list.single().jsonObject.money("adjusted_total"))
            assertEquals(
                "forbidden",
                client.api(e.LIST_CLOSED_BILLS.method, e.LIST_CLOSED_BILLS.path + "?date=$today", f.kitchen).problem(),
            )
            assertEquals(listOf("query.date"), client.api(e.LIST_CLOSED_BILLS, f.waiter).errorFields())
            assertEquals(0, client.api(e.LIST_CLOSED_BILLS.method, e.LIST_CLOSED_BILLS.path + "?date=2020-01-01", f.waiter).items().size)
        }

    @Test
    fun `IT-ORD-22 the auto-close job closes a session whose only order was cancelled, and its bill, with nothing sold`() =
        ordersApp {
            val f = client.floor()
            client.api(e.CANCEL_GUEST_ORDER, f.guest, "orderId" to client.placed(f.guest, line(f.tea)).str("id"))
            val other = client.floor("B")
            client.placed(other.guest, line(other.tea))
            listOf(f.session, other.session).forEach {
                AuthFixtures.sql("UPDATE table_sessions SET last_activity_at = now() - interval '3 hours' WHERE id = ?::uuid", it)
            }
            val run = client.job(Endpoints.Jobs.SESSION_AUTO_CLOSE, TestKeys.CRON_SECRET).json()
            assertEquals("1", run.str("closed"), "the session with an order on its bill stays open")
            assertEquals(
                listOf("closed", "0"),
                client.bill(f.manager, f.session).let { listOf(it.str("status"), it.obj("total").str("amount")) },
            )
            assertEquals("open", client.bill(other.manager, other.session).str("status"))
            assertEquals(0, count("SELECT count(*) FROM daily_sales"))
            assertEquals(listOf("BillClosed", "TableSessionClosed"), eventsOf(f.id).takeLast(2))
        }
}
