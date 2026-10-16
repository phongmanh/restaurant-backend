package io.github.phongmanh.restaurant.tables

import io.github.phongmanh.restaurant.core.application.RateLimiter
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.MutableClock
import io.github.phongmanh.restaurant.support.NoLimits
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.str
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonObject
import org.koin.core.module.Module
import kotlin.test.assertEquals

/** The service with [clock], and no rate limits unless the test passes a [limiter]. */
fun tablesApp(
    clock: MutableClock = MutableClock(),
    limiter: RateLimiter? = NoLimits,
    overrides: List<Module> = emptyList(),
    block: suspend ApplicationTestBuilder.() -> Unit,
) = authApp(clock = clock, limiter = limiter, overrides = overrides, block = block)

private val e = Endpoints.Tables

/** A table that must be created; returns its body. */
suspend fun HttpClient.table(
    token: String,
    number: String,
    extra: String = "",
): JsonObject =
    api(e.CREATE_TABLE, token, body = """{"number": "$number"$extra}""").also { assertEquals(201, it.status.value, "table $number") }.json()

/** The QR token of a table's current printed code. */
suspend fun HttpClient.qrToken(
    token: String,
    tableId: String,
): String = api(e.GET_QR_CODE, token, "tableId" to tableId).json().str("url").substringAfter("/t/")

/** A scan: the QR token and a device, as the customer app sends them. */
suspend fun HttpClient.scan(
    qrToken: String,
    device: String = UuidV7.next().toString(),
    ip: String = "203.0.113.7",
): HttpResponse =
    api(e.OPEN_OR_JOIN_SESSION, null, body = """{"qr_token": "$qrToken", "device_id": "$device"}""") {
        header("x-real-ip", ip)
    }

/** A scan that must succeed; returns the session token and the session ID. */
suspend fun HttpClient.seat(
    qrToken: String,
    device: String = UuidV7.next().toString(),
): Pair<String, String> {
    val grant = scan(qrToken, device).also { assertEquals(200, it.status.value, "scan") }.json()
    return grant.str("session_token") to grant.getValue("session").let { it as JsonObject }.str("id")
}

/** `If-Match` with [version]. */
fun ifMatch(version: Int): HttpRequestBuilder.() -> Unit = { header(HttpHeaders.IfMatch, "\"$version\"") }

/** A request with the cron secret, or [secret] instead. */
suspend fun HttpClient.job(
    path: String,
    secret: String?,
): HttpResponse =
    request(path) {
        method = HttpMethod.Get
        secret?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

/** The audit actions in [restaurant]'s chain that start with [prefix]. */
fun auditOf(
    restaurant: RestaurantId,
    vararg prefix: String,
): List<String> =
    AuthFixtures
        .query("SELECT action FROM audit_events WHERE restaurant_id = ? ORDER BY chain_seq", restaurant.value) { it.getString(1) }
        .filter { action -> prefix.any { action.startsWith(it) } }

/** The event types written for [restaurant], in order. */
fun eventsOf(restaurant: RestaurantId): List<String> =
    AuthFixtures.query("SELECT type FROM domain_events WHERE restaurant_id = ? ORDER BY seq", restaurant.value) { it.getString(1) }

fun count(
    sql: String,
    vararg params: Any?,
): Int = AuthFixtures.query(sql, *params) { it.getInt(1) }.single()
