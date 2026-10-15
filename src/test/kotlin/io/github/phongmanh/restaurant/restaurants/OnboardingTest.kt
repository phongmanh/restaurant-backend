package io.github.phongmanh.restaurant.restaurants

import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.TestKeys
import io.github.phongmanh.restaurant.support.accessToken
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.errorFields
import io.github.phongmanh.restaurant.support.items
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.obj
import io.github.phongmanh.restaurant.support.postJson
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** IT-REST-01 to IT-REST-03 (restaurants §5, FR-040). */
class OnboardingTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val body =
        """
        {"name": "Bún Chả Hương Liên", "address": "24 Lê Văn Hưu, Hà Nội", "languages": ["vi", "en"],
         "owner_contact": {"name": "Hương", "email": "owner@bun.example", "phone": "+84901234567"},
         "first_manager": {"email": "Quang@Bun.example", "display_name": "Quang"}}
        """.trimIndent()

    private suspend fun HttpClient.onboard(
        token: String,
        key: String?,
        json: String = body,
    ): HttpResponse =
        api(Endpoints.Admin.ONBOARD_RESTAURANT, token, body = json) {
            key?.let { header("Idempotency-Key", it) }
        }

    private fun tokenOf(link: String) = link.substringAfter("#")

    @Test
    fun `IT-REST-01 onboarding makes an empty restaurant, an invited manager and a link that leads to a sign-in`() =
        authApp {
            val a = Tenants.create("A", "manager@a.example")
            AuthFixtures.staff(a.id, "waiter@a.example", "waiter")
            val (_, admin) = Tenants.admin("admin@platform.example")

            val created = client.onboard(admin, "onboard-1")
            assertEquals(HttpStatusCode.Created, created.status)
            val answer = created.json()
            val restaurant = answer.obj("restaurant")
            assertEquals("/v1/admin/restaurants/${restaurant.str("id")}", created.headers[HttpHeaders.Location])
            assertEquals("Asia/Ho_Chi_Minh", restaurant.str("time_zone"), "the default time zone")
            assertEquals("Hương", restaurant.obj("owner_contact").str("name"), "the admin view")
            assertEquals("invited", answer.obj("first_manager").str("status"))
            assertEquals("quang@bun.example", answer.obj("first_manager").str("email"), "stored lower-cased")
            val link = answer.obj("invitation").str("link")
            assertTrue(link.startsWith("${TestKeys.STAFF_APP}/invite#"), link)

            val inspected = client.postJson("/v1/auth/links/inspect", """{"token": "${tokenOf(link)}"}""").json()
            assertEquals("Bún Chả Hương Liên", inspected.str("restaurant_name"))
            assertEquals("manager", inspected.str("role"))
            val accepted =
                client.postJson("/v1/auth/invitations/accept", """{"token": "${tokenOf(link)}", "password": "a long enough passphrase"}""")
            assertEquals(HttpStatusCode.NoContent, accepted.status)
            val manager = client.accessToken("quang@bun.example", "a long enough passphrase")

            val own = client.api(Endpoints.Restaurants.GET_OWN_RESTAURANT, manager).json()
            assertEquals(restaurant.str("id"), own.str("id"))
            assertFalse("owner_contact" in own, "staff never see the owner contact")
            val members = client.api(Endpoints.Staff.LIST_MEMBERS, manager).items()
            assertEquals(listOf("quang@bun.example"), members.map { it.jsonObject.str("email") }, "none of A's staff")
            val actions = client.api(Endpoints.Audit.LIST_OWN_AUDIT_EVENTS, manager).items().map { it.jsonObject.str("action") }
            assertEquals(
                listOf("auth.sign_in.succeeded", "auth.invitation.accepted", "staff.invited", "restaurant.onboarded"),
                actions,
                "its own chain, newest first, from the onboarding on",
            )
        }

    @Test
    fun `IT-REST-02 a retried onboarding makes one restaurant, and the replay's fresh link replaces the first`() =
        authApp {
            val (_, admin) = Tenants.admin("admin@platform.example")
            val first = client.onboard(admin, "onboard-2")
            val replay = client.onboard(admin, "onboard-2")
            assertEquals(HttpStatusCode.Created, replay.status)
            assertEquals("true", replay.headers["Idempotent-Replayed"])
            assertEquals(first.headers[HttpHeaders.Location], replay.headers[HttpHeaders.Location])
            assertEquals(1, AuthFixtures.query("SELECT count(*) FROM restaurants") { it.getInt(1) }.single())

            val firstLink = tokenOf(first.json().obj("invitation").str("link"))
            val secondLink = tokenOf(replay.json().obj("invitation").str("link"))
            assertTrue(firstLink != secondLink)
            assertEquals("link_invalid", client.postJson("/v1/auth/links/inspect", """{"token": "$firstLink"}""").problem())
            assertEquals(HttpStatusCode.OK, client.postJson("/v1/auth/links/inspect", """{"token": "$secondLink"}""").status)

            val stored = AuthFixtures.query("SELECT convert_from(response_body, 'UTF8') FROM idempotency_keys") { it.getString(1) }.single()
            assertFalse("invitation" in stored || firstLink in stored || secondLink in stored, "no one-time secret is stored")

            assertEquals("idempotency_key_reused", client.onboard(admin, "onboard-2", body.replace("Quang", "Minh")).problem())
            assertEquals("idempotency_key_required", client.onboard(admin, key = null).problem())

            // Once the manager has accepted, a replay has no invitation to give.
            client.postJson("/v1/auth/invitations/accept", """{"token": "$secondLink", "password": "a long enough passphrase"}""")
            assertEquals("member_state_conflict", client.onboard(admin, "onboard-2").problem())
        }

    @Test
    fun `IT-REST-03 a staff token on the admin routes is 404 and no token is 401`() =
        authApp {
            val a = Tenants.create("A", "manager@a.example")
            for (method in listOf(HttpMethod.Get, HttpMethod.Post)) {
                val withStaff = client.api(method, "/v1/admin/restaurants", a.managerToken, body = body) { header("Idempotency-Key", "k") }
                assertEquals(HttpStatusCode.NotFound, withStaff.status, "$method with a staff token")
                assertEquals(HttpStatusCode.Unauthorized, client.api(method, "/v1/admin/restaurants", null, body = body).status)
            }
            assertEquals(1, AuthFixtures.query("SELECT count(*) FROM restaurants") { it.getInt(1) }.single())
        }

    @Test
    fun `onboarding refuses an email in use, and bad fields, without creating anything`() =
        authApp {
            Tenants.create("A", "quang@bun.example")
            val (_, admin) = Tenants.admin("admin@platform.example")
            val inUse = client.onboard(admin, "onboard-3")
            assertEquals(HttpStatusCode.Conflict, inUse.status)
            assertEquals("email_in_use", inUse.problem())

            val bad =
                client.onboard(
                    admin,
                    "onboard-4",
                    """{"name": "", "time_zone": "UTC+7", "languages": ["en"], "opening_hours": {"mon": [{"opens": "10:00", "closes": "14:00"},
                       {"opens": "12:00", "closes": "15:00"}]}, "first_manager": {"email": "nope"}}""",
                )
            assertEquals("validation_failed", bad.problem())
            assertEquals(
                listOf(
                    "/name",
                    "/address",
                    "/time_zone",
                    "/languages",
                    "/opening_hours/mon",
                    "/first_manager/email",
                    "/first_manager/display_name",
                ),
                bad.errorFields(),
            )
            assertEquals(1, AuthFixtures.query("SELECT count(*) FROM restaurants") { it.getInt(1) }.single())
        }
}
