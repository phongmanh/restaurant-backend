package io.github.phongmanh.restaurant.core.http

import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.menu.category
import io.github.phongmanh.restaurant.menu.item
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.str
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * IT-CORE-11 (core-and-auth §5, CR-012): each `PATCH` update and its `POST <path>/update` twin give
 * the same answers to the same requests: `401`, `400`, `404`, `428`, `412` and the `200` with the
 * next version's `ETag`. Clients send the `POST`, because Vercel's edge turns a `PATCH`'s `200` into
 * its own `412` (D-1); the feature tests send the `POST` too.
 */
class UpdateTwinTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    /** One update: its two registry entries, who may send it, its path, and a body that changes something. */
    private class Update(
        val patch: Endpoint,
        val post: Endpoint,
        val token: String,
        val change: String,
        vararg val path: Pair<String, Any>,
    ) {
        /** The path with its last ID replaced by one that doesn't exist; none when the path names no resource. */
        val unknown: Array<Pair<String, Any>>? =
            path.lastOrNull()?.let { (name, _) -> (path.dropLast(1) + (name to UuidV7.next().toString())).toTypedArray() }
    }

    /**
     * The seven updates. The manager's and the admin's each get a member and a restaurant of their own,
     * as each pair runs from version 0.
     */
    private suspend fun HttpClient.updates(): List<Update> {
        val a = Tenants.create("A", "manager@a.example")
        val b = Tenants.create("B", "manager@b.example")
        val (waiter, _) = a.member("waiter@a.example", "waiter")
        val (kitchen, _) = a.member("kitchen@a.example", "kitchen")
        val (_, admin) = Tenants.admin("admin@platform.example")
        val categoryId = category(a.managerToken, "Phở")
        val itemId = item(a.managerToken, categoryId, "Phở bò").str("id")
        val tableId = api(Endpoints.Tables.CREATE_TABLE, a.managerToken, body = """{"number": "1"}""").json().str("id")
        val manager = a.managerToken
        val name = """{"name": "Twin"}"""
        val displayName = """{"display_name": "Twin"}"""
        val nameEn = """{"name_en": "Twin"}"""
        val r = Endpoints.Restaurants
        val st = Endpoints.Staff
        val ad = Endpoints.Admin
        val m = Endpoints.Menu
        val t = Endpoints.Tables
        return listOf(
            Update(r.UPDATE_OWN_RESTAURANT, r.UPDATE_OWN_RESTAURANT_POST, manager, name),
            Update(st.UPDATE_MEMBER, st.UPDATE_MEMBER_POST, manager, displayName, "staffId" to waiter),
            Update(ad.UPDATE_RESTAURANT, ad.UPDATE_RESTAURANT_POST, admin, name, "restaurantId" to b.id),
            Update(
                st.UPDATE_RESTAURANT_MEMBER,
                st.UPDATE_RESTAURANT_MEMBER_POST,
                admin,
                displayName,
                "restaurantId" to a.id,
                "staffId" to kitchen,
            ),
            Update(m.UPDATE_MENU_CATEGORY, m.UPDATE_MENU_CATEGORY_POST, manager, nameEn, "categoryId" to categoryId),
            Update(m.UPDATE_MENU_ITEM, m.UPDATE_MENU_ITEM_POST, manager, nameEn, "itemId" to itemId),
            Update(t.UPDATE_TABLE, t.UPDATE_TABLE_POST, manager, """{"seats": 6}""", "tableId" to tableId),
        )
    }

    @Test
    fun `IT-CORE-11 every PATCH update and its POST twin answer alike`() =
        authApp {
            val updates = client.updates()
            assertEquals(
                Endpoints.all.filter { it.method == HttpMethod.Patch }.toSet(),
                updates.map { it.patch }.toSet(),
                "every PATCH update is covered",
            )

            for (update in updates) {
                // The PATCH goes first, from version 0; its success makes version 1, where the POST starts.
                val viaPatch = client.answers(update, update.patch, version = 0)
                val viaPost = client.answers(update, update.post, version = 1)
                assertEquals(viaPatch, viaPost, "${update.patch} and ${update.post}")
                val expected =
                    listOfNotNull(
                        "no token" to "401 unauthenticated",
                        "malformed body" to "400 malformed_request",
                        update.unknown?.let { "unknown ID" to "404 not_found" },
                        "no If-Match" to "428 precondition_required",
                        "stale If-Match" to "412 precondition_failed",
                    )
                assertEquals(expected, viaPatch.dropLast(1), "${update.patch}")
                assertTrue(viaPatch.last().second.startsWith("200 ETag"), "${update.patch}: ${viaPatch.last()}")
            }
        }

    /**
     * The answers of [endpoint] to the same six requests, as `(case, "<status> <code or ETag>")`. The
     * success's `ETag` is given relative to [version], so the two methods' lists compare equal.
     */
    private suspend fun HttpClient.answers(
        update: Update,
        endpoint: Endpoint,
        version: Int,
    ): List<Pair<String, String>> {
        val ifMatch: (Int) -> io.ktor.client.request.HttpRequestBuilder.() -> Unit = { v -> { header(HttpHeaders.IfMatch, "\"$v\"") } }
        val cases =
            listOfNotNull(
                "no token" to suspend { api(endpoint, null, *update.path, body = update.change, configure = ifMatch(version)) },
                "malformed body" to suspend { api(endpoint, update.token, *update.path, body = "{", configure = ifMatch(version)) },
                update.unknown?.let {
                    "unknown ID" to suspend { api(endpoint, update.token, *it, body = update.change, configure = ifMatch(version)) }
                },
                "no If-Match" to suspend { api(endpoint, update.token, *update.path, body = update.change) },
                "stale If-Match" to
                    suspend { api(endpoint, update.token, *update.path, body = update.change, configure = ifMatch(version + 5)) },
                "success" to suspend { api(endpoint, update.token, *update.path, body = update.change, configure = ifMatch(version)) },
            )
        return cases.map { (case, send) ->
            val response = send()
            val text = response.bodyAsText()
            val detail =
                if (response.status.value == 200) {
                    val etag = response.headers[HttpHeaders.ETag]
                    "ETag +${etag?.trim('"')?.toInt()?.minus(version)}"
                } else {
                    Json
                        .parseToJsonElement(text)
                        .jsonObject
                        .getValue("code")
                        .jsonPrimitive.content
                }
            case to "${response.status.value} $detail"
        }
    }
}
