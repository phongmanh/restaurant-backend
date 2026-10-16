package io.github.phongmanh.restaurant.support

import io.github.phongmanh.restaurant.core.auth.crypto.Audience
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.http.ApiJson
import io.github.phongmanh.restaurant.core.http.Endpoint
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One API call as a client makes it: JSON in and out, a bearer token, the IP header Vercel's edge sets. */
suspend fun HttpClient.api(
    method: HttpMethod,
    path: String,
    token: String?,
    body: String? = null,
    configure: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    request(path) {
        this.method = method
        token?.let { bearerAuth(it) }
        header("x-real-ip", "203.0.113.7")
        header("User-Agent", "restaurant-tests")
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        configure()
    }

/** Calls a registry entry, with its `{name}` placeholders filled from [path]. */
suspend fun HttpClient.api(
    endpoint: Endpoint,
    token: String?,
    vararg path: Pair<String, Any>,
    body: String? = null,
    configure: HttpRequestBuilder.() -> Unit = {},
): HttpResponse = api(endpoint.method, endpoint.pathOf(*path), token, body, configure)

suspend fun HttpResponse.json(): JsonObject = ApiJson.parseToJsonElement(bodyAsText()).jsonObject

suspend fun HttpResponse.items(): JsonArray = json().getValue("items").jsonArray

/** The problem's `code`. */
suspend fun HttpResponse.problem(): String = json().str("code")

/** The `pointer`s of a `validation_failed` problem's errors. */
suspend fun HttpResponse.errorFields(): List<String> = json().getValue("errors").jsonArray.map { it.jsonObject.str("field") }

fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

/** A restaurant with an active manager and the manager's access token. */
class TestTenant(
    val id: RestaurantId,
    val manager: StaffId,
    val managerToken: String,
) {
    /** Another active member of this restaurant, with an access token. */
    fun member(
        email: String,
        role: String,
    ): Pair<StaffId, String> {
        val id = AuthFixtures.staff(this.id, email, role)
        return id to AuthFixtures.accessToken(id)
    }
}

object Tenants {
    fun create(
        name: String,
        managerEmail: String,
    ): TestTenant {
        val id = AuthFixtures.restaurant(name)
        val manager = AuthFixtures.staff(id, managerEmail)
        return TestTenant(id, manager, AuthFixtures.accessToken(manager))
    }

    /** An active platform admin and their access token. */
    fun admin(email: String): Pair<StaffId, String> {
        val id = AuthFixtures.admin(email)
        return id to AuthFixtures.accessToken(id, Audience.ADMIN)
    }
}

/**
 * Registry entries whose routes aren't built yet. Phase 4 removes each group, or single entries, in
 * the change that builds them; it must be empty at G4. IT-CORE-10 fails both when a route exists for
 * an entry listed here and when an entry missing from here has no route.
 */
val notYetRouted: Set<Endpoint> =
    setOf(
        // FR-022 (Should) waits for TBD-13, the image store (owner, 2026-10-15).
        Endpoints.Menu.PUT_MENU_ITEM_IMAGE,
        Endpoints.Menu.DELETE_MENU_ITEM_IMAGE,
    ) +
        listOf(
            Endpoints.Orders,
            Endpoints.Realtime,
            Endpoints.Reports,
        ).flatMap { it.entries }

/** The TOTP secret an authenticator app reads from the enrolment's Base32 text. */
fun decodeBase32(text: String): ByteArray {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    var buffer = 0
    var bits = 0
    val out = mutableListOf<Byte>()
    for (c in text) {
        buffer = (buffer shl 5) or alphabet.indexOf(c)
        bits += 5
        if (bits >= 8) {
            out += (buffer shr (bits - 8)).toByte()
            bits -= 8
        }
    }
    return out.toByteArray()
}
