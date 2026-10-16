package io.github.phongmanh.restaurant.menu

import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.auth.http.BearerAuthenticator
import io.github.phongmanh.restaurant.core.domain.DeviceId
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.TableId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.CallAuthenticator
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.RouteGroup
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.str
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.server.request.header
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.koin.dsl.module
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Table-session tokens come with the tables feature (step 6). Until then a guest of a restaurant is
 * the header [GUEST_HEADER] naming it, on the guest routes only; everything else goes to the real
 * authenticator, so a guest call without it is still `401`.
 */
const val GUEST_HEADER = "X-Test-Guest-Restaurant"

private class TestGuests(
    private val real: CallAuthenticator,
) : CallAuthenticator {
    override suspend fun authenticate(
        call: io.ktor.server.application.ApplicationCall,
        endpoint: io.github.phongmanh.restaurant.core.http.Endpoint,
    ): Caller {
        val restaurant = call.request.header(GUEST_HEADER)
        if (endpoint.group != RouteGroup.GUEST || restaurant == null) return real.authenticate(call, endpoint)
        return Caller.Guest(
            DeviceId(UuidV7.next()),
            TableSessionId(UuidV7.next()),
            TableId(UuidV7.next()),
            RestaurantId(UUID.fromString(restaurant)),
        )
    }
}

/** The service with the guest stand-in, and no rate limits. */
fun menuApp(block: suspend ApplicationTestBuilder.() -> Unit) =
    authApp(overrides = listOf(module { single<CallAuthenticator> { TestGuests(BearerAuthenticator(get())) } }), block = block)

private val e = Endpoints.Menu

suspend fun HttpClient.newCategory(
    token: String,
    nameVi: String,
    extra: String = "",
): HttpResponse = api(e.CREATE_MENU_CATEGORY, token, body = """{"name_vi": "$nameVi"$extra}""")

/** A category that must be created; returns its ID. */
suspend fun HttpClient.category(
    token: String,
    nameVi: String,
    extra: String = "",
): String = newCategory(token, nameVi, extra).also { assertEquals(201, it.status.value, "category $nameVi") }.json().str("id")

suspend fun HttpClient.newItem(
    token: String,
    categoryId: String,
    nameVi: String,
    price: String = "65000",
    extra: String = "",
): HttpResponse =
    api(
        e.CREATE_MENU_ITEM,
        token,
        body = """{"category_id": "$categoryId", "name_vi": "$nameVi", "price": {"amount": "$price", "currency": "VND"}$extra}""",
    )

/** An item that must be created; returns its body. */
suspend fun HttpClient.item(
    token: String,
    categoryId: String,
    nameVi: String,
    price: String = "65000",
    extra: String = "",
): JsonObject = newItem(token, categoryId, nameVi, price, extra).also { assertEquals(201, it.status.value, "item $nameVi") }.json()

suspend fun HttpClient.guestMenu(
    restaurant: RestaurantId,
    query: String = "",
    etag: String? = null,
): HttpResponse =
    api(e.GET_GUEST_MENU.method, e.GET_GUEST_MENU.path + query, null) {
        header(GUEST_HEADER, restaurant.toString())
        etag?.let { header(HttpHeaders.IfNoneMatch, it) }
    }

fun JsonObject.array(name: String): JsonArray = getValue(name).jsonArray

fun JsonArray.objects(): List<JsonObject> = map { it.jsonObject }

/** The menu version a restaurant is at. */
fun menuVersion(restaurant: RestaurantId): Long =
    AuthFixtures.query("SELECT version FROM menu_state WHERE restaurant_id = ?", restaurant.value) { it.getLong(1) }.single()

/** The audit actions in [restaurant]'s chain that start with `menu.`. */
fun menuAudit(restaurant: RestaurantId): List<String> =
    AuthFixtures
        .query("SELECT action FROM audit_events WHERE restaurant_id = ? ORDER BY chain_seq", restaurant.value) { it.getString(1) }
        .filter { it.startsWith("menu.") }
