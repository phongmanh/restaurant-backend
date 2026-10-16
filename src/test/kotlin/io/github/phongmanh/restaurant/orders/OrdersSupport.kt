package io.github.phongmanh.restaurant.orders

import io.github.phongmanh.restaurant.core.application.RateLimiter
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.IDEMPOTENCY_KEY_HEADER
import io.github.phongmanh.restaurant.menu.array
import io.github.phongmanh.restaurant.menu.category
import io.github.phongmanh.restaurant.menu.item
import io.github.phongmanh.restaurant.menu.objects
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Guests
import io.github.phongmanh.restaurant.support.MutableClock
import io.github.phongmanh.restaurant.support.NoLimits
import io.github.phongmanh.restaurant.support.Seat
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestTenant
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.str
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonObject
import org.koin.core.module.Module
import kotlin.test.assertEquals

/** The service with [clock], and no rate limits unless the test passes a [limiter]. */
fun ordersApp(
    clock: MutableClock = MutableClock(),
    limiter: RateLimiter? = NoLimits,
    overrides: List<Module> = emptyList(),
    block: suspend ApplicationTestBuilder.() -> Unit,
) = authApp(clock = clock, limiter = limiter, overrides = overrides, block = block)

/**
 * A restaurant ready to take orders: its staff (with tokens), a menu of phở (65,000 đồng; size
 * required: small +0 or large +10,000) and trà đá (5,000 đồng), and a guest seated at a table.
 */
class Floor(
    val tenant: TestTenant,
    val waiterId: StaffId,
    val waiter: String,
    val kitchen: String,
    val pho: String,
    val small: String,
    val large: String,
    val tea: String,
    val seat: Seat,
) {
    val id: RestaurantId get() = tenant.id
    val manager: String get() = tenant.managerToken
    val guest: String get() = seat.token
    val session: String get() = seat.session.toString()
}

suspend fun HttpClient.floor(name: String = "A"): Floor {
    val domain = name.lowercase() + ".example"
    val tenant = Tenants.create(name, "manager@$domain")
    val (waiterId, waiter) = tenant.member("waiter@$domain", "waiter")
    val (_, kitchen) = tenant.member("kitchen@$domain", "kitchen")
    val category = category(tenant.managerToken, "Món chính")
    val pho =
        item(
            tenant.managerToken,
            category,
            "Phở bò",
            "65000",
            """, "name_en": "Beef pho", "option_groups": [
              {"name_vi": "Cỡ", "name_en": "Size", "min_choices": 1, "max_choices": 1,
               "options": [{"name_vi": "Nhỏ"},
                           {"name_vi": "Lớn", "name_en": "Large", "price_delta": {"amount": "10000", "currency": "VND"}}]}]""",
        )
    val (small, large) =
        pho
            .array("option_groups")
            .objects()
            .single()
            .array("options")
            .objects()
            .map { it.str("id") }
    val tea = item(tenant.managerToken, category, "Trà đá", "5000").str("id")
    return Floor(tenant, waiterId, waiter, kitchen, pho.str("id"), small, large, tea, Guests.seated(tenant.id))
}

/** One line of a `PlaceOrderRequest`. */
fun line(
    item: String,
    quantity: Int = 1,
    options: List<String> = emptyList(),
    note: String? = null,
): String {
    val opts = if (options.isEmpty()) "" else """, "option_ids": [${options.joinToString { "\"$it\"" }}]"""
    val text = note?.let { """, "note": "$it"""" } ?: ""
    return """{"item_id": "$item", "quantity": $quantity$opts$text}"""
}

fun orderBody(vararg lines: String) = """{"lines": [${lines.joinToString()}]}"""

private val e = Endpoints.Orders

/** A guest's order with an `Idempotency-Key` ([key], new by default). */
suspend fun HttpClient.guestOrder(
    token: String,
    body: String,
    key: String = UuidV7.next().toString(),
): HttpResponse = api(e.PLACE_GUEST_ORDER, token, body = body) { header(IDEMPOTENCY_KEY_HEADER, key) }

/** A guest's order that must be placed; returns it. */
suspend fun HttpClient.placed(
    token: String,
    vararg lines: String,
): JsonObject = guestOrder(token, orderBody(*lines)).also { assertEquals(201, it.status.value, "order") }.json()

/** A staff step with `If-Match`. */
suspend fun HttpClient.step(
    token: String,
    orderId: String,
    status: String,
    version: Int,
    reason: String? = null,
): HttpResponse {
    val extra = reason?.let { """, "reason": $it""" } ?: ""
    return api(e.CHANGE_ORDER_STATUS, token, "orderId" to orderId, body = """{"status": "$status"$extra}""") {
        header(HttpHeaders.IfMatch, "\"$version\"")
    }
}

/** The staff view of a session's bill. */
suspend fun HttpClient.bill(
    token: String,
    session: String,
): JsonObject = api(e.GET_SESSION_BILL, token, "sessionId" to session).json()

/** A close with `If-Match` [version] and an `Idempotency-Key`. */
suspend fun HttpClient.close(
    token: String,
    session: String,
    version: Int,
    body: String,
    key: String = UuidV7.next().toString(),
): HttpResponse =
    api(e.CLOSE_TABLE_SESSION, token, "sessionId" to session, body = body) {
        header(HttpHeaders.IfMatch, "\"$version\"")
        header(IDEMPOTENCY_KEY_HEADER, key)
    }

fun cash(amount: Long) = """{"payment": {"method": "cash", "amount_received": {"amount": "$amount", "currency": "VND"}}}"""

/** The money amount of a `Money` field, as a number. */
fun JsonObject.money(name: String): Long = (getValue(name) as JsonObject).str("amount").toLong()

fun JsonObject.int(name: String): Int = str(name).toInt()

/** The rows of a daily sales table of [restaurant], as `column=value` lists, for comparisons. */
fun dailySales(
    restaurant: RestaurantId,
    sql: String,
): List<String> =
    AuthFixtures.query(sql, restaurant.value) { rs ->
        (1..rs.metaData.columnCount).joinToString(" ") { "${rs.metaData.getColumnName(it)}=${rs.getString(it)}" }
    }
