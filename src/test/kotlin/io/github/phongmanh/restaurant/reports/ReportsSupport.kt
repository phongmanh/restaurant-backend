package io.github.phongmanh.restaurant.reports

import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.TableId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.orders.Floor
import io.github.phongmanh.restaurant.orders.bill
import io.github.phongmanh.restaurant.orders.close
import io.github.phongmanh.restaurant.orders.int
import io.github.phongmanh.restaurant.orders.money
import io.github.phongmanh.restaurant.orders.placed
import io.github.phongmanh.restaurant.orders.step
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Guests
import io.github.phongmanh.restaurant.support.Seat
import io.github.phongmanh.restaurant.support.TestKeys
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.str
import io.github.phongmanh.restaurant.tables.job
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals

/** The report for [from] to [to], as [token] asks for it. */
suspend fun HttpClient.report(
    token: String,
    from: Any?,
    to: Any?,
): HttpResponse {
    val query = listOfNotNull(from?.let { "from=$it" }, to?.let { "to=$it" }).joinToString("&")
    val e = Endpoints.Reports.DAILY_SALES_REPORT
    return api(e.method, e.path + if (query.isEmpty()) "" else "?$query", token)
}

/** A report that must succeed. */
suspend fun HttpClient.reportOf(
    token: String,
    from: LocalDate,
    to: LocalDate,
): JsonObject = report(token, from, to).also { assertEquals(200, it.status.value, "report") }.json()

/** The day [date] of a report. */
fun JsonObject.day(date: LocalDate): JsonObject =
    getValue("days").jsonArray.map { it.jsonObject }.single {
        it.str("date") ==
            date.toString()
    }

/** A day's (or the totals') figures as one line: sessions, orders, gross, corrections, net. */
fun JsonObject.figures(): String =
    "sessions=${str("sessions")} orders=${str("orders")} gross=${money("gross")} " +
        "corrections=${money("corrections")} net=${money("net")}"

/** The payments of a day or of the totals, one line each. */
fun JsonObject.payments(): List<String> =
    getValue("payments").jsonArray.map { it.jsonObject }.map {
        "${it.str("method")} sessions=${it.str("sessions")} amount=${it.money("amount")} corrections=${it.money("corrections")}"
    }

fun payment(
    method: String,
    amount: Long,
) = """{"payment": {"method": "$method", "amount_received": {"amount": "$amount", "currency": "VND"}}}"""

/** Moves [orderId] through the kitchen to `served`. */
suspend fun HttpClient.serve(
    f: Floor,
    orderId: String,
) = listOf("accepted", "preparing", "ready", "served").forEachIndexed { v, s ->
    assertEquals(200, step(f.kitchen, orderId, s, v).status.value, s)
}

/** A sale at [seat]: one order of [lines], served, and the bill closed with [method]; returns the closed bill. */
suspend fun HttpClient.sell(
    f: Floor,
    seat: Seat,
    vararg lines: String,
    method: String = "cash",
): JsonObject {
    serve(f, placed(seat.token, *lines).str("id"))
    val open = bill(f.waiter, seat.session.toString())
    val closed = close(f.waiter, seat.session.toString(), open.int("version"), payment(method, open.money("total")))
    assertEquals(200, closed.status.value, "close")
    return closed.json()
}

/** A manager's correction to a closed bill, with `If-Match` [version]. */
suspend fun HttpClient.correct(
    f: Floor,
    session: String,
    version: Int,
    amount: Long,
    method: String = "cash",
): JsonObject {
    val response =
        api(
            Endpoints.Orders.ADJUST_CLOSED_BILL,
            f.manager,
            "sessionId" to session,
            body = """{"amount": {"amount": "$amount", "currency": "VND"}, "payment_method": "$method", "reason_code": "goodwill"}""",
        ) { header(HttpHeaders.IfMatch, "\"$version\"") }
    assertEquals(201, response.status.value, "correction")
    return response.json()
}

/** A new open session at an existing table (its last one closed), and a guest token for it. */
fun seatAt(
    restaurant: RestaurantId,
    table: TableId,
): Seat {
    val session = UuidV7.next()
    AuthFixtures.sql(
        "INSERT INTO table_sessions (id, restaurant_id, table_id, status, opened_at, last_activity_at) " +
            "VALUES (?, ?, ?, 'open', now(), now())",
        session,
        restaurant.value,
        table.value,
    )
    return Seat(restaurant, table, TableSessionId(session), Guests.token(restaurant, TableSessionId(session)))
}

/** A fixed-offset zone where the local time at [now] is [local], to the minute. */
fun zoneWhere(
    now: Instant,
    local: LocalTime,
): ZoneOffset {
    val seconds = Math.floorMod(local.toSecondOfDay() - now.atOffset(ZoneOffset.UTC).toLocalTime().toSecondOfDay(), 86_400)
    return ZoneOffset.ofTotalSeconds((if (seconds > 43_200) seconds - 86_400 else seconds) / 60 * 60)
}

fun setTimeZone(
    restaurant: RestaurantId,
    zone: String,
) = AuthFixtures.sql("UPDATE restaurants SET time_zone = ? WHERE id = ?", zone, restaurant.value)

/**
 * Moves everything sold and corrected on [from] to [to]: the bills' and corrections' local days
 * and the daily rows alike, as if it had happened then. Tests build past days this way, as tokens
 * and the clock can't go back.
 */
fun moveDay(
    restaurant: RestaurantId,
    from: LocalDate,
    to: LocalDate,
) {
    val columns =
        listOf("bills" to "business_date", "bill_adjustments" to "business_date") +
            listOf("daily_sales", "daily_sales_payment", "daily_sales_table", "daily_sales_item").map { it to "sales_date" }
    columns.forEach { (table, column) ->
        AuthFixtures.sql("UPDATE $table SET $column = ? WHERE restaurant_id = ? AND $column = ?", to, restaurant.value, from)
    }
}

/** Every daily row of [restaurant] on [date], one line each, in key order: what the rebuild must leave as it was. */
fun dailyRows(
    restaurant: RestaurantId,
    date: LocalDate,
): List<String> =
    listOf(
        "SELECT 'day', sessions, orders, gross, corrections, correction_count FROM daily_sales",
        "SELECT method, sessions, amount, corrections FROM daily_sales_payment",
        "SELECT table_id::text, table_number, sessions, gross FROM daily_sales_table",
        "SELECT item_id::text, name_vi, name_en, quantity, revenue FROM daily_sales_item",
    ).flatMap { select ->
        AuthFixtures.query("$select WHERE restaurant_id = ? AND sales_date = ? ORDER BY 1", restaurant.value, date) { rs ->
            (1..rs.metaData.columnCount).joinToString(" ") { rs.getString(it) ?: "-" }
        }
    }

private val reconcile = Endpoints.Jobs.SALES_RECONCILE

/** Runs the nightly rebuild; returns its answer. */
suspend fun HttpClient.reconcile(): JsonObject =
    job(reconcile, TestKeys.CRON_SECRET).also { assertEquals(200, it.status.value, "reconcile") }.json()

/** `reconciled_through last_mismatch_on` of [restaurant], or `null` without a row. */
fun reconciliation(restaurant: RestaurantId): String? =
    AuthFixtures
        .query("SELECT reconciled_through, last_mismatch_on FROM sales_reconciliation WHERE restaurant_id = ?", restaurant.value) {
            "${it.getString(1)} ${it.getString(2) ?: "-"}"
        }.singleOrNull()
