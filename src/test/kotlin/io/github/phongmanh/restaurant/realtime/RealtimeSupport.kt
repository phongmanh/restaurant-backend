package io.github.phongmanh.restaurant.realtime

import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.http.Endpoint
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.orders.ordersApp
import io.github.phongmanh.restaurant.realtime.application.RealtimePublisher
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.FakeAbly
import io.github.phongmanh.restaurant.support.MutableClock
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.str
import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.koin.dsl.module
import kotlin.test.assertEquals

/** The service with [ably] as its Ably, [clock], and no rate limits. */
fun realtimeApp(
    ably: FakeAbly = FakeAbly(),
    clock: MutableClock = MutableClock(),
    block: suspend ApplicationTestBuilder.() -> Unit,
) = ordersApp(clock = clock, overrides = listOf(module { single<RealtimePublisher> { ably } }), block = block)

val guestEvents: Endpoint = Endpoints.Realtime.GUEST_CATCH_UP
val staffEvents: Endpoint = Endpoints.Realtime.STAFF_CATCH_UP

/** A catch-up that must succeed: [after] and [limit] as given (absent when `null`). */
suspend fun HttpClient.catchUp(
    endpoint: Endpoint,
    token: String,
    after: Long? = null,
    limit: Int? = null,
): JsonObject {
    val query = listOfNotNull(after?.let { "after=$it" }, limit?.let { "limit=$it" }).joinToString("&")
    val response = api(endpoint.method, endpoint.path + if (query.isEmpty()) "" else "?$query", token)
    assertEquals(200, response.status.value, "catch-up")
    return response.json()
}

fun JsonObject.cursor(): Long = getValue("cursor").jsonPrimitive.long

/** The page's events as `type:aggregate id:version`. */
fun JsonObject.eventList(): List<String> =
    getValue("events").jsonArray.map {
        val e = it.jsonObject
        val aggregate = e.getValue("aggregate").jsonObject
        "${e.str("type")}:${aggregate.str("id")}:${aggregate["version"]?.jsonPrimitive?.content}"
    }

fun JsonObject.seqs(): List<Long> =
    getValue("events").jsonArray.map {
        it.jsonObject
            .getValue("seq")
            .jsonPrimitive.long
    }

fun JsonObject.snapshots(name: String): List<JsonObject> = getValue(name).jsonArray.map { it.jsonObject }

/** Outbox rows of [restaurant] not yet sent. */
fun unsent(restaurant: RestaurantId): Int =
    AuthFixtures
        .query("SELECT count(*) FROM outbox WHERE restaurant_id = ? AND published_at IS NULL", restaurant.value) { it.getInt(1) }
        .single()

/** The IDs of [restaurant]'s events, in `seq` order. */
fun eventIds(restaurant: RestaurantId): List<String> =
    AuthFixtures.query("SELECT id FROM domain_events WHERE restaurant_id = ? ORDER BY seq", restaurant.value) { it.getString(1) }
