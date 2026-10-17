package io.github.phongmanh.restaurant.restaurants

import io.github.phongmanh.restaurant.core.auth.crypto.Totp
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.restaurants.ops.runOperator
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.MutableClock
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.TestKeys
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.decodeBase32
import io.github.phongmanh.restaurant.support.field
import io.github.phongmanh.restaurant.support.items
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.obj
import io.github.phongmanh.restaurant.support.postJson
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.signIn
import io.github.phongmanh.restaurant.support.str
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** IT-REST-12 (restaurants §8, NFR-SEC-04) and the operator's commands. */
class AdminAccountsTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val e = Endpoints.Admin

    private suspend fun HttpClient.accept(
        link: String,
        code: String? = null,
    ) = postJson(
        "/v1/auth/invitations/accept",
        """{"token": "${link.substringAfter("#")}", "password": "an admin passphrase"${code?.let { ", \"totp_code\": \"$it\"" } ?: ""}}""",
    )

    @Test
    fun `IT-REST-12 an admin can't act on themselves, the last admin stays, and a TOTP reset re-enrols through the link`() {
        val clock = MutableClock()
        authApp(clock) {
            val (me, token) = Tenants.admin("me@platform.example")
            val (other, otherToken) = Tenants.admin("other@platform.example")
            for (endpoint in listOf(e.DEACTIVATE_ADMIN, e.PASSWORD_RESET_ADMIN, e.TOTP_RESET_ADMIN)) {
                val self = client.api(endpoint, token, "staffId" to me)
                assertEquals(HttpStatusCode.Conflict, self.status, endpoint.toString())
                assertEquals("self_action_not_allowed", self.problem(), endpoint.toString())
            }

            val earlierReset =
                client
                    .api(e.PASSWORD_RESET_ADMIN, token, "staffId" to other)
                    .json()
                    .str("link")
                    .substringAfter("#")
            val reset = client.api(e.TOTP_RESET_ADMIN, token, "staffId" to other)
            assertEquals(HttpStatusCode.Created, reset.status)
            val link = reset.json().str("link")
            assertTrue(link.startsWith("${TestKeys.ADMIN_APP}/invite#"), link)
            assertEquals(HttpStatusCode.Unauthorized, client.api(e.LIST_ADMINS, otherToken).status, "their sessions ended")
            val inspected = client.postJson("/v1/auth/links/inspect", """{"token": "${link.substringAfter("#")}"}""")
            assertEquals("true", inspected.field("totp_enrolment_required"))
            assertEquals("member_state_conflict", client.api(e.PASSWORD_RESET_ADMIN, token, "staffId" to other).problem(), "invited now")

            val secret =
                decodeBase32(
                    client
                        .accept(link)
                        .json()
                        .obj("totp_setup")
                        .str("secret"),
                )
            assertEquals(HttpStatusCode.NoContent, client.accept(link, Totp.code(secret, Totp.step(clock.now.epochSecond))).status)
            clock.advance(kotlin.time.Duration.parse("30s"))
            val mfa = client.signIn("other@platform.example", "an admin passphrase").field("mfa_token")
            val code = Totp.code(secret, Totp.step(clock.now.epochSecond))
            val signedIn = client.postJson("/v1/auth/mfa", """{"mfa_token": "$mfa", "code": "$code"}""")
            assertEquals(HttpStatusCode.OK, signedIn.status, "the admin is back with a new authenticator")
            val stale = client.postJson("/v1/auth/links/inspect", """{"token": "$earlierReset"}""")
            assertEquals(HttpStatusCode.NotFound, stale.status, "a reset link from before the TOTP reset stays void")

            val actions =
                AuthFixtures.query(
                    "SELECT action FROM audit_events WHERE restaurant_id IS NULL ORDER BY chain_seq",
                ) { it.getString(1) }
            assertTrue("admin.totp_reset" in actions, actions.toString())
            assertEquals(
                listOf("totp_reset"),
                AuthFixtures.query(
                    "SELECT DISTINCT revoke_reason FROM staff_sessions WHERE staff_id = ? AND revoked_at IS NOT NULL",
                    other.value,
                ) {
                    it.getString(1)
                },
            )
        }
    }

    @Test
    fun `IT-REST-12 two admins deactivating each other at once leave one active`() =
        authApp {
            repeat(5) { round ->
                TestDatabase.truncate()
                val (x, xToken) = Tenants.admin("x$round@platform.example")
                val (y, yToken) = Tenants.admin("y$round@platform.example")
                val statuses =
                    coroutineScope {
                        listOf(
                            async { client.api(e.DEACTIVATE_ADMIN, xToken, "staffId" to y) },
                            async { client.api(e.DEACTIVATE_ADMIN, yToken, "staffId" to x) },
                        ).map { it.await().status }
                    }
                assertEquals(1, statuses.count { it == HttpStatusCode.OK }, "round $round: $statuses")
                assertEquals(
                    1,
                    AuthFixtures
                        .query(
                            "SELECT count(*) FROM staff_accounts WHERE is_platform_admin AND status = 'active'",
                        ) { it.getInt(1) }
                        .single(),
                )
            }
        }

    @Test
    fun `admins invite, list, reset and deactivate other admins`() =
        authApp {
            val (_, token) = Tenants.admin("me@platform.example")
            val invited = client.api(e.INVITE_ADMIN, token, body = """{"email": "New@Platform.example", "display_name": "Nam"}""")
            assertEquals(HttpStatusCode.Created, invited.status)
            val admin = invited.json().obj("admin")
            assertEquals("new@platform.example", admin.str("email"))
            assertEquals("invited", admin.str("status"))
            assertEquals(
                "email_in_use",
                client.api(e.INVITE_ADMIN, token, body = """{"email": "new@platform.example", "display_name": "N"}""").problem(),
            )
            assertEquals("validation_failed", client.api(e.INVITE_ADMIN, token, body = """{"email": "nope"}""").problem())

            val listed = client.api(e.LIST_ADMINS, token).items().map { it.jsonObject.str("display_name") }
            assertEquals(listOf("Admin", "Nam"), listed)

            val (other, _) = Tenants.admin("other@platform.example")
            val resetLink = client.api(e.PASSWORD_RESET_ADMIN, token, "staffId" to other)
            assertEquals(HttpStatusCode.Created, resetLink.status)
            assertTrue(resetLink.json().str("link").startsWith("${TestKeys.ADMIN_APP}/reset-password#"))
            assertEquals("deactivated", client.api(e.DEACTIVATE_ADMIN, token, "staffId" to other).json().str("status"))
            assertEquals("deactivated", client.api(e.DEACTIVATE_ADMIN, token, "staffId" to other).json().str("status"), "again: no-op")
            assertEquals("member_state_conflict", client.api(e.TOTP_RESET_ADMIN, token, "staffId" to other).problem())

            val restaurantStaff = AuthFixtures.staff(AuthFixtures.restaurant(), "lan@a.example")
            for (endpoint in listOf(e.DEACTIVATE_ADMIN, e.PASSWORD_RESET_ADMIN, e.TOTP_RESET_ADMIN)) {
                for ((case, id) in listOf("not an admin" to restaurantStaff, "nobody" to UuidV7.next())) {
                    val response = client.api(endpoint, token, "staffId" to id)
                    assertEquals(HttpStatusCode.NotFound, response.status, "$endpoint: $case")
                    assertEquals("not_found", response.problem(), "$endpoint: $case")
                }
            }
            for (query in listOf("?limit=0", "?limit=201", "?cursor=nope")) {
                val bad = client.api(HttpMethod.Get, e.LIST_ADMINS.path + query, token)
                assertEquals(HttpStatusCode.BadRequest, bad.status, query)
                assertEquals("validation_failed", bad.problem(), query)
            }
        }

    @Test
    fun `the operator invites the first admin and resets the last admin's TOTP, and nothing more`() {
        TestDatabase.truncate()

        fun run(vararg args: String): Pair<Int, String> {
            val out = ByteArrayOutputStream()
            val code = runOperator(args.toList(), { TestDatabase.config }, PrintStream(out, true, Charsets.UTF_8))
            return code to out.toString(Charsets.UTF_8)
        }
        assertEquals(2, run("invite-admin", "first@platform.example").first, "a display name is needed")
        val (code, printed) = run("invite-admin", "First@Platform.example", "Phương", "Anh")
        assertEquals(0, code, printed)
        assertTrue("${TestKeys.ADMIN_APP}/invite#" in printed, printed)
        val name = AuthFixtures.query("SELECT display_name FROM staff_accounts WHERE email = 'first@platform.example'") { it.getString(1) }
        assertEquals(listOf("Phương Anh"), name)

        assertEquals(0, run("reset-admin-totp", "first@platform.example").first, "an invited admin gets a fresh link")
        AuthFixtures.admin("second@platform.example")
        assertEquals(1, run("invite-admin", "third@platform.example", "Ba").first, "an admin is active: use the API")
        assertEquals(0, run("reset-admin-totp", "second@platform.example").first, "the only active admin")
        AuthFixtures.admin("fourth@platform.example")
        AuthFixtures.admin("fifth@platform.example")
        assertEquals(1, run("reset-admin-totp", "fourth@platform.example").first, "another admin can do it through the API")
        val audited =
            AuthFixtures.query("SELECT action, actor_type FROM audit_events WHERE restaurant_id IS NULL ORDER BY chain_seq") {
                it.getString(1) to it.getString(2)
            }
        assertEquals(listOf("admin.invited", "admin.totp_reset", "admin.totp_reset").map { it to "system" }, audited)
    }
}
