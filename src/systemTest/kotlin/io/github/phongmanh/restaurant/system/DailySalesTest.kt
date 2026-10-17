package io.github.phongmanh.restaurant.system

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * test-plan §5 AT-050 (FR-050) and §4 ST-21: the daily report against the bills it adds up, in
 * restaurant A. The scenario closes three bills now, one per payment method, with a cancelled order
 * and a rejected line, then checks the last seven days of the report against every closed bill
 * the API lists for them: totals, payments and tables per day, and for today the items too.
 *
 * Staging's clock can't be moved, so the bills are today's and those earlier runs left; bills that
 * cross midnight UTC (07:00 in Ho Chi Minh City) on staging come only from a run made between
 * 00:00 and 07:00 there. CI moves the clock across it (IT-ORD-16, IT-REP-02).
 */
class DailySalesTest {
    private val a = Staging.a
    private val api = Staging.api

    @Test
    fun `AT-050 the daily report matches the bills per day, payment method, table and item, without cancelled lines`() {
        val today =
            a.withTables(3) { (t1, t2, t3) ->
                val first = a.seat(t1.str("id"))
                val kept = a.serve(first.order(line(item("Fresh spring rolls"), 2), line(item("Iced tea"))))
                val cancelled = first.order(line(item("Coconut water"), 3))
                first.expect(OK, "POST", "/v1/guest/orders/${cancelled.str("id")}/cancel")
                a.serve(rejectLine(first.order(line(item("Shaking beef")), line(item("Saigon beer"), 2)), "Shaking beef"))
                val closed = a.closeSession(first.sessionId, "cash", 1_000_000)
                val lines = closed.arrOf("lines").map { it.str("name_en") }
                assertTrue("Coconut water" !in lines && "Shaking beef" !in lines, "the bill has no cancelled or rejected line: $lines")
                assertTrue(kept.str("id") in closed.arrOf("lines").map { it.str("order_id") })

                a.seat(t2.str("id")).let {
                    a.serve(it.order(line(item("Caramelised fish in clay pot")), line(item("Saigon beer"), 3)))
                    a.closeSession(it.sessionId, "card")
                }
                a.seat(t3.str("id")).let {
                    a.serve(it.order(line(item("Broken rice with pork"), 2)))
                    a.closeSession(it.sessionId, "bank_transfer")
                }
                LocalDate.parse(closed.str("business_date"))
            }
        val from = today.minusDays(WEEK - 1)
        val report = api.expect(OK, "GET", "/v1/staff/reports/daily-sales?from=$from&to=$today", token = a.manager).json
        assertEquals("Asia/Ho_Chi_Minh", report.str("time_zone"))
        val days = report.arrOf("days").associateBy { LocalDate.parse(it.str("date")) }
        var bills = 0
        for (offset in 0 until WEEK) {
            val date = from.plusDays(offset)
            val closedThatDay = closedBills(date)
            bills += closedThatDay.size
            val day = days[date]
            if (closedThatDay.isEmpty()) {
                assertTrue(day == null || day.int("sessions") == 0, "$date: no bills, no sales")
                continue
            }
            requireNotNull(day) { "$date has ${closedThatDay.size} bills but no day in the report" }
            assertDayMatches(date, day, closedThatDay)
            if (date == today) assertItemsMatch(day, closedThatDay)
        }
        println("[AT-050] $from to $today: $bills closed bills in restaurant A checked against the report")
        assertEquals(bills, report.obj("totals").int("sessions"), "the week's total counts every bill")
    }

    private fun item(nameEn: String) = a.item(nameEn).str("id")

    /** The kitchen accepts the order, then rejects one of its lines (orders §5.3); returns the order. */
    private fun rejectLine(
        order: JsonObject,
        nameEn: String,
    ): JsonObject {
        val accepted = a.step(order, "accepted")
        val lineId = accepted.arrOf("lines").single { it.str("name_en") == nameEn }.str("id")
        return api
            .expect(
                OK,
                "POST",
                "/v1/staff/orders/${accepted.str("id")}/lines/$lineId/reject",
                buildJsonObject { putJsonObject("reason") { put("code", "item_unavailable") } },
                a.kitchen,
                ifMatch(accepted.int("version")),
            ).json
    }

    /**
     * Every paid bill closed on [date], from the staff's list of closed bills, page by page. A bill
     * of 0 (every order cancelled) closes without a payment and is no sale, so the ledger leaves it out.
     */
    private fun closedBills(date: LocalDate): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var cursor: String? = null
        do {
            val page =
                api
                    .expect(
                        OK,
                        "GET",
                        "/v1/staff/bills?date=$date&limit=100" + (cursor?.let { "&cursor=$it" } ?: ""),
                        token = a.manager,
                    ).json
            all += page.arrOf("items")
            cursor = page["next_cursor"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        } while (cursor != null)
        return all.filter { it.money("total") > 0 }
    }

    private fun assertDayMatches(
        date: LocalDate,
        day: JsonObject,
        bills: List<JsonObject>,
    ) {
        val gross = bills.sumOf { it.money("total") }
        val corrections = bills.sumOf { b -> b["adjusted_total"]?.let { b.money("adjusted_total") - b.money("total") } ?: 0L }
        assertEquals(bills.size, day.int("sessions"), "$date: sessions")
        assertEquals(gross, day.money("gross"), "$date: gross")
        assertEquals(corrections, day.money("corrections"), "$date: corrections")
        assertEquals(gross + corrections, day.money("net"), "$date: net")

        val byMethod = bills.groupBy { it.str("payment_method") }
        val payments = day.arrOf("payments").filter { it.int("sessions") > 0 }.associateBy { it.str("method") }
        assertEquals(byMethod.keys, payments.keys, "$date: payment methods")
        for ((method, paid) in byMethod) {
            assertEquals(paid.size, payments.getValue(method).int("sessions"), "$date: $method sessions")
            assertEquals(paid.sumOf { it.money("total") }, payments.getValue(method).money("amount"), "$date: $method amount")
        }

        val byTable = bills.groupBy { it.obj("table").str("id") }
        val tables = day.arrOf("tables").associateBy { it.str("table_id") }
        assertEquals(byTable.keys, tables.keys, "$date: tables")
        for ((table, sales) in byTable) {
            assertEquals(
                sales.size,
                tables.getValue(table).int("sessions"),
                "$date: table ${sales.first().obj("table").str("number")} sessions",
            )
            assertEquals(sales.sumOf { it.money("total") }, tables.getValue(table).money("gross"), "$date: table gross")
        }
    }

    /**
     * Today's orders and items: each bill's own count and lines (cancelled orders and rejected lines
     * aren't on a bill), added up per item.
     */
    private fun assertItemsMatch(
        day: JsonObject,
        bills: List<JsonObject>,
    ) {
        val details = bills.map { api.expect(OK, "GET", "/v1/staff/table-sessions/${it.str("session_id")}/bill", token = a.manager).json }
        assertEquals(details.sumOf { it.int("order_count") }, day.int("orders"), "today: orders")
        val sold = details.flatMap { it.arrOf("lines") }.groupBy { it.str("name_vi") }
        val listed = day.arrOf("top_items_by_quantity") + day.arrOf("top_items_by_revenue")
        assertTrue(listed.isNotEmpty(), "today's report lists items")
        for (entry in listed) {
            val name = entry.str("name_vi")
            val ofItem = sold[name].orEmpty()
            assertEquals(ofItem.sumOf { it.int("quantity") }, entry.int("quantity"), "today: $name quantity")
            assertEquals(ofItem.sumOf { it.money("line_total") }, entry.money("revenue"), "today: $name revenue")
        }
        val revenue = listed.associate { it.str("name_vi") to it.money("revenue") }
        assertTrue(revenue.values.all { it > 0 }, "no item at zero: cancelled and rejected lines aren't counted")
    }

    private companion object {
        const val WEEK = 7L
    }
}
