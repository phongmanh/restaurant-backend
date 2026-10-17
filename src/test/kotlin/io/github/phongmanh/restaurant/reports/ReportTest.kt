package io.github.phongmanh.restaurant.reports

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.orders.application.OrderEvents
import io.github.phongmanh.restaurant.orders.bill
import io.github.phongmanh.restaurant.orders.close
import io.github.phongmanh.restaurant.orders.floor
import io.github.phongmanh.restaurant.orders.int
import io.github.phongmanh.restaurant.orders.line
import io.github.phongmanh.restaurant.orders.money
import io.github.phongmanh.restaurant.orders.ordersApp
import io.github.phongmanh.restaurant.orders.placed
import io.github.phongmanh.restaurant.orders.step
import io.github.phongmanh.restaurant.realtime.application.AppendedEvent
import io.github.phongmanh.restaurant.realtime.application.EventAudience
import io.github.phongmanh.restaurant.realtime.application.EventLog
import io.github.phongmanh.restaurant.realtime.application.EventSubject
import io.github.phongmanh.restaurant.realtime.application.OutboxEventLog
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Guests
import io.github.phongmanh.restaurant.support.MutableClock
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.obj
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.koin.dsl.module
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** IT-REP-01 to IT-REP-05 and IT-REP-08 to IT-REP-11 (reports §6, §10; FR-050). */
class ReportTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val clock = MutableClock()

    /** A restaurant where it's noon now, so its yesterday is past the rebuild's 03:00. */
    private fun atNoon(restaurant: RestaurantId): LocalDate {
        val zone = zoneWhere(clock.now, LocalTime.NOON)
        setTimeZone(restaurant, zone.id)
        return LocalDate.ofInstant(clock.now, zone)
    }

    @Test
    fun `IT-REP-01 a closed bill shows in today's report at once, and a close that fails after the ledger leaves it unchanged`() {
        val failing = AtomicBoolean(false)
        val events =
            module {
                single<EventLog> {
                    val real = OutboxEventLog(get(), get(), get(), get())
                    object : EventLog {
                        override fun append(
                            tx: TxScope,
                            restaurantId: RestaurantId,
                            type: String,
                            subject: EventSubject,
                            audience: EventAudience,
                            sessionId: TableSessionId?,
                        ): AppendedEvent {
                            check(!(failing.get() && type == OrderEvents.BILL_CLOSED)) { "event log down" }
                            return real.append(tx, restaurantId, type, subject, audience, sessionId)
                        }
                    }
                }
            }
        ordersApp(clock, overrides = listOf(events)) {
            val f = client.floor()
            val closed = client.sell(f, f.seat, line(f.pho, options = listOf(f.small)), line(f.tea, 2))
            val today = LocalDate.parse(closed.str("business_date"))
            val report = client.reportOf(f.manager, today, today)
            val zone = AuthFixtures.query("SELECT time_zone FROM restaurants WHERE id = ?", f.id.value) { it.getString(1) }.single()
            assertEquals(listOf(today.toString(), today.toString(), zone), listOf("from", "to", "time_zone").map(report::str))
            val day = report.day(today)
            assertEquals("sessions=1 orders=1 gross=75000 corrections=0 net=75000", day.figures())
            assertEquals(day.figures(), report.obj("totals").figures())
            assertEquals(listOf("cash sessions=1 amount=75000 corrections=0"), day.payments())
            val table =
                day
                    .getValue("tables")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(
                listOf(f.seat.table.toString(), "1", "75000"),
                listOf(table.str("table_id"), table.str("sessions"), table.money("gross").toString()),
            )
            assertEquals(listOf("Trà đá", "Phở bò"), day.topItems("top_items_by_quantity").map { it.str("name_vi") })
            assertEquals(listOf("Phở bò", "Trà đá"), day.topItems("top_items_by_revenue").map { it.str("name_vi") })
            assertEquals(
                listOf("Beef pho"),
                day.topItems("top_items_by_revenue").mapNotNull {
                    it["name_en"]?.let { _ ->
                        it.str("name_en")
                    }
                },
            )

            failing.set(true)
            val before = dailyRows(f.id, today)
            val seat = Guests.seated(f.id)
            placeAndServe(f, client, seat.token)
            val version = client.bill(f.waiter, seat.session.toString()).int("version")
            assertEquals(
                HttpStatusCode.InternalServerError,
                client.close(f.waiter, seat.session.toString(), version, payment("cash", 5000)).status,
            )
            assertEquals(before, dailyRows(f.id, today), "the close rolled back, its sales with it")
            assertEquals(
                "sessions=1 orders=1 gross=75000 corrections=0 net=75000",
                client.reportOf(f.manager, today, today).day(today).figures(),
            )
        }
    }

    @Test
    fun `IT-REP-02 a bill counts on the local day of its close, in each restaurant's own time zone`() =
        ordersApp(clock) {
            val now = clock.now
            val a = client.floor("A")
            val b = client.floor("B")
            // A: opened at 23:40, closed at 00:20. B: an hour behind, where it's still 23:20 the day before.
            val zoneA = zoneWhere(now, LocalTime.of(0, 20))
            val zoneB = java.time.ZoneOffset.ofTotalSeconds(zoneA.totalSeconds - 3600)
            setTimeZone(a.id, zoneA.id)
            setTimeZone(b.id, zoneB.id)
            AuthFixtures.sql(
                "UPDATE table_sessions SET opened_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now.minus(40, ChronoUnit.MINUTES)),
                a.seat.session.value,
            )
            val dayA = LocalDate.ofInstant(now, zoneA)
            val dayB = LocalDate.ofInstant(now, zoneB)
            assertEquals(dayA.minusDays(1), dayB)
            assertEquals(
                dayA.minusDays(1),
                LocalDate.ofInstant(now.minus(40, ChronoUnit.MINUTES), zoneA),
                "the session opened the day before",
            )

            assertEquals(dayA.toString(), client.sell(a, a.seat, line(a.tea)).str("business_date"))
            assertEquals(dayB.toString(), client.sell(b, b.seat, line(b.tea)).str("business_date"))
            val reportA = client.reportOf(a.manager, dayA.minusDays(1), dayA.plusDays(1))
            assertEquals(zoneA.id, reportA.str("time_zone"))
            assertEquals(listOf("0", "1", "0"), reportA.days().map { it.str("sessions") })
            val reportB = client.reportOf(b.manager, dayB.minusDays(1), dayB.plusDays(1))
            assertEquals(listOf("0", "1", "0"), reportB.days().map { it.str("sessions") })
        }

    @Test
    fun `IT-REP-03 cancelled and rejected orders and rejected lines don't count, nor does a bill of 0`() =
        ordersApp(clock) {
            val f = client.floor()
            val today = atNoon(f.id)
            val cancelled = client.placed(f.guest, line(f.tea))
            assertEquals(
                HttpStatusCode.OK,
                client.api(Endpoints.Orders.CANCEL_GUEST_ORDER, f.guest, "orderId" to cancelled.str("id")).status,
            )
            val rejected = client.placed(f.guest, line(f.pho, options = listOf(f.small)))
            assertEquals(
                HttpStatusCode.OK,
                client.step(f.kitchen, rejected.str("id"), "rejected", 0, """{"code": "kitchen_closed"}""").status,
            )
            // An order whose tea is rejected after the kitchen accepted it: only the phở counts.
            val order = client.placed(f.guest, line(f.pho, options = listOf(f.large)), line(f.tea, 2))
            client.rejectLineAndServe(
                f,
                order.str("id"),
                order
                    .getValue("lines")
                    .jsonArray[1]
                    .jsonObject
                    .str("id"),
            )
            val open = client.bill(f.waiter, f.session)
            assertEquals(HttpStatusCode.OK, client.close(f.waiter, f.session, open.int("version"), payment("card", 75_000)).status)

            // A visit whose only order was cancelled closes with 0: not a sale.
            val empty = Guests.seated(f.id)
            val nothing = client.placed(empty.token, line(f.tea))
            client.api(Endpoints.Orders.CANCEL_GUEST_ORDER, empty.token, "orderId" to nothing.str("id"))
            val emptyBill = client.bill(f.waiter, empty.session.toString())
            assertEquals(HttpStatusCode.OK, client.close(f.waiter, empty.session.toString(), emptyBill.int("version"), "{}").status)

            val day = client.reportOf(f.manager, today, today).day(today)
            assertEquals("sessions=1 orders=1 gross=75000 corrections=0 net=75000", day.figures())
            assertEquals(listOf("card sessions=1 amount=75000 corrections=0"), day.payments())
            assertEquals(listOf("Phở bò 1 75000"), day.topItems("top_items_by_quantity").map(::nameQuantityRevenue))
            assertEquals(1, day.getValue("tables").jsonArray.size)

            // The rebuild, counting from the raw rows, agrees.
            moveDay(f.id, today, today.minusDays(1))
            assertEquals("0", client.reconcile().str("mismatches"))
        }

    @Test
    fun `IT-REP-04 a correction counts on the day it's made, in corrections and its method, not in tables or items`() =
        ordersApp(clock) {
            val f = client.floor()
            val today = atNoon(f.id)
            val yesterday = today.minusDays(1)
            val closed = client.sell(f, f.seat, line(f.pho, options = listOf(f.small)), line(f.tea))
            moveDay(f.id, today, yesterday)
            val v = closed.int("version")
            client.correct(f, f.session, v, -5000)
            // The payment method was wrong: −X on the old one, +X on the new one.
            client.correct(f, f.session, v + 1, -65_000, "cash")
            client.correct(f, f.session, v + 2, 65_000, "card")

            val report = client.reportOf(f.manager, yesterday, today)
            assertEquals("sessions=1 orders=1 gross=70000 corrections=0 net=70000", report.day(yesterday).figures())
            val day = report.day(today)
            assertEquals("sessions=0 orders=0 gross=0 corrections=-5000 net=-5000", day.figures())
            assertEquals("3", day.str("correction_count"))
            assertEquals(
                listOf("card sessions=0 amount=0 corrections=65000", "cash sessions=0 amount=0 corrections=-70000"),
                day.payments(),
            )
            assertEquals(
                listOf(0, 0, 0),
                listOf("tables", "top_items_by_quantity", "top_items_by_revenue").map { day.getValue(it).jsonArray.size },
            )
            val totals = report.obj("totals")
            assertEquals("sessions=1 orders=1 gross=70000 corrections=-5000 net=65000", totals.figures())
            assertEquals(
                listOf("card sessions=0 amount=0 corrections=65000", "cash sessions=1 amount=70000 corrections=-70000"),
                totals.payments(),
            )
            assertEquals("0", client.reconcile().str("mismatches"))
        }

    @Test
    fun `IT-REP-05 ten bills sharing items close at once without deadlock, and the figures are the sum`() =
        ordersApp(clock) {
            val f = client.floor()
            val today = atNoon(f.id)
            val seats = (1..10).map { Guests.seated(f.id) }.toMutableList()
            // Two tables have sold once already today.
            (0..1).forEach { i ->
                client.sell(f, seats[i], line(f.pho, options = listOf(f.small)), line(f.tea))
                seats[i] = seatAt(f.id, seats[i].table)
            }
            seats.forEachIndexed { i, seat ->
                val lines = listOf(line(f.pho, options = listOf(f.small)), line(f.tea)).let { if (i % 2 == 0) it else it.reversed() }
                placeAndServe(f, client, seat.token, *lines.toTypedArray())
            }
            val versions = seats.map { client.bill(f.waiter, it.session.toString()).int("version") }
            val answers =
                coroutineScope {
                    seats
                        .mapIndexed {
                            i,
                            seat,
                            ->
                            async { client.close(f.waiter, seat.session.toString(), versions[i], payment("cash", 70_000)) }
                        }.awaitAll()
                }
            assertEquals(List(10) { HttpStatusCode.OK }, answers.map { it.status })
            val day = client.reportOf(f.manager, today, today).day(today)
            assertEquals("sessions=12 orders=12 gross=840000 corrections=0 net=840000", day.figures())
            assertEquals(
                listOf("Phở bò 12 780000", "Trà đá 12 60000"),
                day.topItems("top_items_by_revenue").map { "${it.str("name_vi")} ${it.str("quantity")} ${it.money("revenue")}" },
            )
            assertEquals(
                listOf("2", "2"),
                day
                    .getValue("tables")
                    .jsonArray
                    .map { it.jsonObject.str("sessions") }
                    .filter { it != "1" },
            )
            moveDay(f.id, today, today.minusDays(1))
            assertEquals("0", client.reconcile().str("mismatches"))
        }

    @Test
    fun `IT-REP-08 the report equals the figures computed from the raw rows, for random ranges`() =
        ordersApp(clock) {
            val f = client.floor()
            val today = atNoon(f.id)
            val random = Random(8)
            val seats = MutableList(3) { if (it == 0) f.seat else Guests.seated(f.id) }
            val methods = listOf("cash", "card", "bank_transfer")
            (5 downTo 0).forEach { daysAgo ->
                repeat(random.nextInt(0, 4)) { n ->
                    val t = (daysAgo + n) % seats.size
                    val lines =
                        listOfNotNull(
                            line(f.pho, random.nextInt(1, 4), listOf(if (random.nextBoolean()) f.small else f.large)),
                            random.nextInt(0, 4).takeIf { it > 0 }?.let { line(f.tea, it) },
                        )
                    val closed = client.sell(f, seats[t], *lines.toTypedArray(), method = methods.random(random))
                    if (random.nextInt(3) ==
                        0
                    ) {
                        client.correct(
                            f,
                            seats[t].session.toString(),
                            closed.int("version"),
                            -1000L * random.nextInt(1, 6),
                            methods.random(random),
                        )
                    }
                    seats[t] = seatAt(f.id, seats[t].table)
                }
                if (daysAgo > 0) moveDay(f.id, today, today.minusDays(daysAgo.toLong()))
            }
            repeat(8) {
                val from = today.minusDays(random.nextLong(0, 7))
                val to = from.plusDays(random.nextLong(0, ChronoUnit.DAYS.between(from, today) + 1))
                val report = client.reportOf(f.manager, from, to)
                val raw = RawSales.of(f.id, from, to)
                assertEquals(raw.keys.toList(), report.days().map { LocalDate.parse(it.str("date")) }, "every day listed")
                raw.forEach { (date, expected) -> assertEquals(expected, RawSales.view(report.day(date)), "$date of $from..$to") }
                assertEquals(RawSales.totals(raw.values), report.obj("totals").figures(), "totals of $from..$to")
            }
        }

    @Test
    fun `IT-REP-09 a range over 93 days or ending before it starts is refused, and every day is listed`() =
        ordersApp(clock) {
            val f = client.floor()
            val closed = client.sell(f, f.seat, line(f.tea))
            val today = LocalDate.parse(closed.str("business_date"))

            suspend fun refused(
                from: Any?,
                to: Any?,
            ): List<String> =
                client.report(f.manager, from, to).let { r ->
                    assertEquals("validation_failed", r.problem())
                    r
                        .json()
                        .getValue("errors")
                        .jsonArray
                        .map { it.jsonObject.let { e -> "${e.str("field")} ${e.str("code")}" } }
                }

            assertEquals(listOf("query.to range_too_long"), refused(today.minusDays(93), today))
            assertEquals(listOf("query.to end_before_start"), refused(today, today.minusDays(1)))
            assertEquals(listOf("query.from required", "query.to required"), refused(null, null))
            assertEquals(listOf("query.from invalid_date"), refused("2026-13-01", today))
            val longest = client.reportOf(f.manager, today.minusDays(92), today)
            assertEquals(93, longest.days().size)

            val three = client.reportOf(f.manager, today.minusDays(1), today.plusDays(1))
            assertEquals(listOf(today.minusDays(1), today, today.plusDays(1)).map { it.toString() }, three.days().map { it.str("date") })
            listOf(today.minusDays(1), today.plusDays(1)).forEach { d ->
                val zero = three.day(d)
                assertEquals("sessions=0 orders=0 gross=0 corrections=0 net=0", zero.figures())
                assertEquals(
                    listOf(0, 0, 0, 0),
                    listOf("payments", "tables", "top_items_by_quantity", "top_items_by_revenue").map {
                        zero.getValue(it).jsonArray.size
                    },
                )
            }
        }

    @Test
    fun `IT-REP-10 only a manager reads it, never another restaurant's figures, also while suspended`() =
        ordersApp(clock) {
            val f = client.floor("A")
            val other = client.floor("B")
            val today = LocalDate.parse(client.sell(other, other.seat, line(other.tea)).str("business_date"))
            assertEquals("forbidden", client.report(f.waiter, today, today).problem())
            assertEquals("forbidden", client.report(f.kitchen, today, today).problem())
            assertEquals("forbidden", client.report(f.waiter, "bad", null).problem(), "the role is checked before the query")
            assertEquals("unauthenticated", client.report("not-a-token", today, today).problem())
            assertEquals(
                "sessions=0 orders=0 gross=0 corrections=0 net=0",
                client.reportOf(f.manager, today, today).obj("totals").figures(),
            )
            AuthFixtures.suspend(f.id)
            assertEquals(HttpStatusCode.OK, client.report(f.manager, today, today).status, "allowed when suspended")
        }

    @Test
    fun `IT-REP-10 report reads are limited to 30 a minute per staff member`() =
        ordersApp(clock, limiter = null) {
            val f = client.floor()
            val today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
            repeat(30) { assertEquals(HttpStatusCode.OK, client.report(f.manager, today, today).status) }
            val limited = client.report(f.manager, today, today)
            assertEquals("rate_limited", limited.problem())
            assertTrue(limited.headers[HttpHeaders.RetryAfter] != null)
        }

    @Test
    fun `IT-REP-11 a renamed table and item keep the names they had on past days, through a rebuild too`() =
        ordersApp(clock) {
            val f = client.floor()
            val today = atNoon(f.id)
            val yesterday = today.minusDays(1)
            client.sell(f, f.seat, line(f.pho, options = listOf(f.small)))
            val number =
                AuthFixtures
                    .query(
                        "SELECT number FROM dining_tables WHERE id = ?",
                        f.seat.table.value,
                    ) { it.getString(1) }
                    .single()
            AuthFixtures.sql("UPDATE dining_tables SET number = 'Z-1' WHERE id = ?", f.seat.table.value)
            AuthFixtures.sql(
                "UPDATE menu_items SET name_vi = 'Phở gà', name_en = 'Chicken pho' WHERE id = ?",
                java.util.UUID.fromString(f.pho),
            )
            moveDay(f.id, today, yesterday)
            val before = dailyRows(f.id, yesterday)
            // A broken tally: the rebuild rewrites the day, and keeps the names a close wrote.
            AuthFixtures.sql("UPDATE daily_sales_table SET gross = gross + 1 WHERE restaurant_id = ?", f.id.value)
            AuthFixtures.sql("UPDATE daily_sales_item SET quantity = quantity + 1 WHERE restaurant_id = ?", f.id.value)
            assertEquals("1", client.reconcile().str("mismatches"))
            assertEquals(before, dailyRows(f.id, yesterday))

            val day = client.reportOf(f.manager, yesterday, yesterday).day(yesterday)
            assertEquals(listOf(number), day.getValue("tables").jsonArray.map { it.jsonObject.str("number") })
            assertEquals(
                listOf("Phở bò Beef pho"),
                day.topItems("top_items_by_quantity").map { "${it.str("name_vi")} ${it.str("name_en")}" },
            )
        }
}

private fun JsonObject.days(): List<JsonObject> = getValue("days").jsonArray.map { it.jsonObject }

private fun JsonObject.topItems(name: String): List<JsonObject> = getValue(name).jsonArray.map { it.jsonObject }

/** Places [lines] for the guest and serves the order. */
private suspend fun placeAndServe(
    f: io.github.phongmanh.restaurant.orders.Floor,
    client: io.ktor.client.HttpClient,
    guest: String,
    vararg lines: String = arrayOf(line(f.tea)),
) = client.serve(f, client.placed(guest, *lines).str("id"))

/** Accepts the order, rejects [lineId], and serves the rest. */
private suspend fun io.ktor.client.HttpClient.rejectLineAndServe(
    f: io.github.phongmanh.restaurant.orders.Floor,
    orderId: String,
    lineId: String,
) {
    assertEquals(HttpStatusCode.OK, step(f.kitchen, orderId, "accepted", 0).status)
    val reject =
        api(
            Endpoints.Orders.REJECT_ORDER_LINE,
            f.kitchen,
            "orderId" to orderId,
            "lineId" to lineId,
            body = """{"reason": {"code": "item_unavailable"}}""",
        ) {
            header(HttpHeaders.IfMatch, "\"1\"")
        }
    assertEquals(HttpStatusCode.OK, reject.status)
    listOf("preparing", "ready", "served").forEachIndexed { i, status ->
        assertEquals(
            HttpStatusCode.OK,
            step(
                f.kitchen,
                orderId,
                status,
                i + 2,
            ).status,
        )
    }
}

private fun nameQuantityRevenue(item: JsonObject) = "${item.str("name_vi")} ${item.str("quantity")} ${item.money("revenue")}"

/**
 * The S-5 `check`: a range's figures computed straight from the raw rows (closed bills, their
 * active lines, corrections), in the report's shape, without the code under test.
 */
private object RawSales {
    data class Day(
        val figures: String,
        val payments: List<String>,
        val tables: List<String>,
        val byQuantity: List<String>,
        val byRevenue: List<String>,
    )

    private data class Bill(
        val date: LocalDate,
        val total: Long,
        val orders: Int,
        val method: String,
        val table: String,
    )

    private data class Line(
        val date: LocalDate,
        val item: String,
        val quantity: Int,
        val revenue: Long,
    )

    fun of(
        restaurant: RestaurantId,
        from: LocalDate,
        to: LocalDate,
    ): Map<LocalDate, Day> {
        val r = restaurant.value
        val bills =
            AuthFixtures.query(
                "SELECT b.business_date, b.total, b.order_count, b.payment_method, s.table_id::text FROM bills b " +
                    "JOIN table_sessions s ON s.id = b.session_id WHERE b.restaurant_id = ? AND b.status = 'closed' AND b.total > 0 " +
                    "AND b.business_date BETWEEN ? AND ?",
                r,
                from,
                to,
            ) { Bill(it.getObject(1, LocalDate::class.java), it.getLong(2), it.getInt(3), it.getString(4), it.getString(5)) }
        val corrections =
            AuthFixtures.query(
                "SELECT business_date, payment_method, amount FROM bill_adjustments " +
                    "WHERE restaurant_id = ? AND business_date BETWEEN ? AND ?",
                r,
                from,
                to,
            ) { Triple(it.getObject(1, LocalDate::class.java), it.getString(2), it.getLong(3)) }
        val lines =
            AuthFixtures.query(
                "SELECT b.business_date, l.item_id::text, l.quantity, l.line_total FROM order_lines l " +
                    "JOIN orders o ON o.id = l.order_id JOIN bills b ON b.session_id = o.session_id " +
                    "WHERE b.restaurant_id = ? AND b.status = 'closed' AND b.total > 0 AND b.business_date BETWEEN ? AND ? " +
                    "AND o.status NOT IN ('cancelled', 'rejected') AND l.status = 'active'",
                r,
                from,
                to,
            ) { Line(it.getObject(1, LocalDate::class.java), it.getString(2), it.getInt(3), it.getLong(4)) }
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.associateWith { date ->
            day(bills.filter { it.date == date }, corrections.filter { it.first == date }, lines.filter { it.date == date })
        }
    }

    private fun day(
        bills: List<Bill>,
        corrections: List<Triple<LocalDate, String, Long>>,
        lines: List<Line>,
    ): Day {
        val gross = bills.sumOf { it.total }
        val corrected = corrections.sumOf { it.third }
        val items = lines.groupBy { it.item }.map { (item, of) -> Triple(item, of.sumOf { it.quantity }, of.sumOf { it.revenue }) }
        val byQuantity = compareByDescending<Triple<String, Int, Long>> { it.second }.thenBy { it.first }
        val byRevenue = compareByDescending<Triple<String, Int, Long>> { it.third }.thenBy { it.first }
        return Day(
            figures = "sessions=${bills.size} orders=${bills.sumOf {
                it.orders
            }} gross=$gross corrections=$corrected net=${gross + corrected}",
            payments =
                (bills.map { it.method } + corrections.map { it.second }).toSortedSet().map { m ->
                    val paid = bills.filter { it.method == m }
                    val methodCorrections = corrections.filter { it.second == m }.sumOf { it.third }
                    "$m sessions=${paid.size} amount=${paid.sumOf { it.total }} corrections=$methodCorrections"
                },
            tables = bills.groupBy { it.table }.map { (t, of) -> "$t ${of.size} ${of.sumOf { it.total }}" }.sorted(),
            byQuantity = items.sortedWith(byQuantity).take(TOP).map(::item),
            byRevenue = items.sortedWith(byRevenue).take(TOP).map(::item),
        )
    }

    private const val TOP = 10

    private fun item(i: Triple<String, Int, Long>) = "${i.first} ${i.second} ${i.third}"

    /** The same view of a report day. */
    fun view(day: JsonObject) =
        Day(
            figures = day.figures(),
            payments = day.payments(),
            tables =
                day
                    .getValue("tables")
                    .jsonArray
                    .map {
                        it.jsonObject
                    }.map { "${it.str("table_id")} ${it.str("sessions")} ${it.money("gross")}" }
                    .sorted(),
            byQuantity =
                day
                    .topItems(
                        "top_items_by_quantity",
                    ).map { "${it.str("item_id")} ${it.str("quantity")} ${it.money("revenue")}" },
            byRevenue =
                day
                    .topItems(
                        "top_items_by_revenue",
                    ).map { "${it.str("item_id")} ${it.str("quantity")} ${it.money("revenue")}" },
        )

    fun totals(days: Collection<Day>): String {
        val parts = days.map { d -> d.figures.split(" ").associate { it.substringBefore("=") to it.substringAfter("=").toLong() } }
        return listOf("sessions", "orders", "gross", "corrections", "net").joinToString(" ") { k -> "$k=${parts.sumOf { it.getValue(k) }}" }
    }
}
