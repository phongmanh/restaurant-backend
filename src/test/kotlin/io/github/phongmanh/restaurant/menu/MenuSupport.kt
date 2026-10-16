package io.github.phongmanh.restaurant.menu

import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Guests
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.str
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals

/** The service with no rate limits. */
fun menuApp(block: suspend ApplicationTestBuilder.() -> Unit) = authApp(block = block)

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
    api(e.GET_GUEST_MENU.method, e.GET_GUEST_MENU.path + query, Guests.seated(restaurant).token) {
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
