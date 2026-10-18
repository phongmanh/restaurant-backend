package io.github.phongmanh.restaurant.restaurants

import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.errorFields
import io.github.phongmanh.restaurant.support.items
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.obj
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** IT-REST-04 (restaurants §4, FR-044) and the admins' restaurant views. */
class RestaurantProfileTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private suspend fun HttpClient.edit(
        token: String,
        body: String,
        version: String? = "\"0\"",
    ) = api(Endpoints.Restaurants.UPDATE_OWN_RESTAURANT_POST, token, body = body) { version?.let { header(HttpHeaders.IfMatch, it) } }

    @Test
    fun `IT-REST-04 a manager edits the profile, bad fields are 400, a stale If-Match 412, and a waiter 403`() =
        authApp {
            val a = Tenants.create("A", "lan@a.example")
            val (_, waiter) = a.member("mai@a.example", "waiter")
            val read = client.api(Endpoints.Restaurants.GET_OWN_RESTAURANT, waiter)
            assertEquals(HttpStatusCode.OK, read.status, "every role reads it")
            assertEquals("\"0\"", read.headers[HttpHeaders.ETag])

            val hours = """{"mon": [{"opens": "17:00", "closes": "01:00"}, {"opens": "10:00", "closes": "14:00"}]}"""
            val edited = client.edit(a.managerToken, """{"name": "Phở Thìn", "languages": ["vi", "en"], "opening_hours": $hours}""")
            assertEquals(HttpStatusCode.OK, edited.status)
            assertEquals("\"1\"", edited.headers[HttpHeaders.ETag])
            val body = edited.json()
            assertEquals("Phở Thìn", body.str("name"))
            assertEquals(
                listOf("10:00", "17:00"),
                body
                    .obj("opening_hours")
                    .getValue("mon")
                    .jsonArray
                    .map { it.jsonObject.str("opens") },
            )
            val details =
                AuthFixtures.query(
                    "SELECT details::text FROM audit_events WHERE action = 'restaurant.profile_updated'",
                ) { it.getString(1) }
            assertEquals(listOf("""{"fields": ["name", "languages", "opening_hours"]}"""), details, "field names, never values")

            val bad =
                client.edit(
                    a.managerToken,
                    """{"time_zone": "UTC+7", "languages": ["en"], "opening_hours": {"tue": [{"opens": "10:00", "closes": "14:00"},
                       {"opens": "13:00", "closes": "15:00"}]}, "address": ""}""",
                    "\"1\"",
                )
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertEquals(listOf("/address", "/time_zone", "/languages", "/opening_hours/tue"), bad.errorFields())
            assertEquals("validation_failed", client.edit(a.managerToken, """{"time_zone": "Asia/Saigon_Not"}""", "\"1\"").problem())
            assertEquals("validation_failed", client.edit(a.managerToken, "{}", "\"1\"").problem(), "nothing to change")

            assertEquals("precondition_failed", client.edit(a.managerToken, """{"name": "Late"}""", "\"0\"").problem())
            assertEquals("precondition_required", client.edit(a.managerToken, """{"name": "Late"}""", null).problem())
            assertEquals(HttpStatusCode.Forbidden, client.edit(waiter, """{"name": "Mine"}""", "\"1\"").status)
            assertEquals("forbidden", client.edit(waiter, """{"time_zone": "UTC+7"}""", null).problem(), "403 before 400 and 428")
        }

    @Test
    fun `only an admin changes the owner contact, and only admins see it`() =
        authApp {
            val a = Tenants.create("A", "lan@a.example")
            val (_, admin) = Tenants.admin("admin@platform.example")
            val contact = """{"owner_contact": {"name": "Hương", "email": "Owner@A.example"}}"""
            val ignored = client.edit(a.managerToken, """{"name": "A2", "owner_contact": {"name": "Me"}}""")
            assertFalse("owner_contact" in ignored.json(), "a manager's owner_contact is ignored")

            val byAdmin =
                client.api(Endpoints.Admin.UPDATE_RESTAURANT_POST, admin, "restaurantId" to a.id, body = contact) {
                    header(HttpHeaders.IfMatch, "\"1\"")
                }
            assertEquals(HttpStatusCode.OK, byAdmin.status)
            assertEquals("owner@a.example", byAdmin.json().obj("owner_contact").str("email"))
            assertEquals("A2", byAdmin.json().str("name"))
            assertFalse("owner_contact" in client.api(Endpoints.Restaurants.GET_OWN_RESTAURANT, a.managerToken).json())
            val missing = client.api(Endpoints.Admin.GET_RESTAURANT, admin, "restaurantId" to "0192aaaa-0000-7000-8000-000000000000")
            assertEquals(HttpStatusCode.NotFound, missing.status)
        }

    @Test
    fun `an admin reads and edits one restaurant, a bad edit is 400, and an unknown restaurant is 404`() =
        authApp {
            val a = Tenants.create("A", "lan@a.example")
            val (_, admin) = Tenants.admin("admin@platform.example")
            val read = client.api(Endpoints.Admin.GET_RESTAURANT, admin, "restaurantId" to a.id)
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals("\"0\"", read.headers[HttpHeaders.ETag])
            assertEquals(a.id.toString(), read.json().str("id"))

            for (body in listOf("""{}""", """{"time_zone": "Mars/Base"}""")) {
                val bad =
                    client.api(Endpoints.Admin.UPDATE_RESTAURANT_POST, admin, "restaurantId" to a.id, body = body) {
                        header(HttpHeaders.IfMatch, "\"0\"")
                    }
                assertEquals(HttpStatusCode.BadRequest, bad.status, body)
                assertEquals("validation_failed", bad.problem(), body)
            }
            assertEquals("\"0\"", client.api(Endpoints.Admin.GET_RESTAURANT, admin, "restaurantId" to a.id).headers[HttpHeaders.ETag])

            val nowhere = "restaurantId" to "0192aaaa-0000-7000-8000-000000000000"
            val edit =
                client.api(Endpoints.Admin.UPDATE_RESTAURANT_POST, admin, nowhere, body = """{"name": "B"}""") {
                    header(HttpHeaders.IfMatch, "\"0\"")
                }
            assertEquals(HttpStatusCode.NotFound, edit.status)
            assertEquals("not_found", edit.problem())
            val suspend = client.api(Endpoints.Admin.SUSPEND_RESTAURANT, admin, nowhere, body = """{"reason": "unpaid"}""")
            assertEquals(HttpStatusCode.NotFound, suspend.status)
            assertEquals("not_found", suspend.problem())
        }

    @Test
    fun `admins list restaurants by name, with a status filter, a name prefix and pages`() =
        authApp {
            listOf("Bún Chả", "bánh mì", "Cơm Tấm", "Phở").forEach { AuthFixtures.restaurant(it) }
            AuthFixtures.suspend(AuthFixtures.restaurant("Bò Né"))
            val (_, admin) = Tenants.admin("admin@platform.example")

            fun names(items: kotlinx.serialization.json.JsonArray) =
                items.map {
                    it.jsonObject
                        .getValue("name")
                        .jsonPrimitive.content
                }
            val b = client.api(HttpMethod.Get, "/v1/admin/restaurants?q=b", admin).items()
            assertEquals(setOf("Bún Chả", "bánh mì", "Bò Né"), names(b).toSet(), "the prefix ignores case")
            val suspended = client.api(HttpMethod.Get, "/v1/admin/restaurants?status=suspended", admin).items()
            assertEquals(listOf("Bò Né"), names(suspended))
            assertTrue(suspended[0].jsonObject.containsKey("suspended_at"))

            val first = client.api(HttpMethod.Get, "/v1/admin/restaurants?limit=3", admin).json()
            val rest = client.api(HttpMethod.Get, "/v1/admin/restaurants?limit=3&cursor=${first.str("next_cursor")}", admin).items()
            assertEquals(5, names(first.getValue("items").jsonArray).size + names(rest).size)
            assertEquals(
                (names(first.getValue("items").jsonArray) + names(rest)).toSet().size,
                5,
                "no restaurant on two pages",
            )
            assertEquals("validation_failed", client.api(HttpMethod.Get, "/v1/admin/restaurants?status=closed", admin).problem())
            assertTrue(
                client.api(HttpMethod.Get, "/v1/admin/restaurants?q=%25", admin).items().isEmpty(),
                "% is a character, not a wildcard",
            )
        }
}
