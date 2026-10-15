package io.github.phongmanh.restaurant.core.auth

import io.github.phongmanh.restaurant.core.auth.crypto.Totp
import io.github.phongmanh.restaurant.core.http.ApiJson
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.MutableClock
import io.github.phongmanh.restaurant.support.Probe
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.TestKeys
import io.github.phongmanh.restaurant.support.accessToken
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.cookie
import io.github.phongmanh.restaurant.support.field
import io.github.phongmanh.restaurant.support.postJson
import io.github.phongmanh.restaurant.support.setCookieHeader
import io.github.phongmanh.restaurant.support.signIn
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonObject
import java.security.SecureRandom
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** IT-AUTH-06 to IT-AUTH-09 and IT-AUTH-12 (core-and-auth §8.2–§8.4, §8.7). */
class SessionTest {
    private val email = "lan@pho.example"
    private val adminEmail = "ops@platform.example"
    private val staffCookie = "__Secure-rt-staff"
    private val adminCookie = "__Secure-rt-admin"

    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private suspend fun HttpClient.refresh(
        token: String?,
        audience: String = "staff",
    ): HttpResponse =
        postJson("/v1/auth/refresh", """{"audience": "$audience"}""") {
            token?.let { header(HttpHeaders.Cookie, "__Secure-rt-$audience=$it") }
        }

    private suspend fun HttpClient.probe(token: String) = get(Probe.STAFF.path) { bearerAuth(token) }

    private suspend fun HttpClient.mfa(
        mfaToken: String,
        code: String,
    ) = postJson("/v1/auth/mfa", """{"mfa_token": "$mfaToken", "code": "$code"}""")

    @Test
    fun `IT-AUTH-06 an admin signs in with a TOTP code, once`() {
        val clock = MutableClock()
        val secret = Totp.newSecret(SecureRandom())
        authApp(clock) {
            AuthFixtures.admin(adminEmail, secret)
            val first = client.signIn(adminEmail)
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals("true", first.field("mfa_required"))
            assertEquals("300", first.field("expires_in"))
            assertTrue(first.headers.getAll(HttpHeaders.SetCookie).isNullOrEmpty(), "no cookie before the TOTP step")

            val code = Totp.code(secret, Totp.step(clock.now.epochSecond))
            val signedIn = client.mfa(first.field("mfa_token"), code)
            assertEquals(HttpStatusCode.OK, signedIn.status)
            assertEquals("admin", signedIn.field("audience"))
            assertNotNull(signedIn.cookie(adminCookie))
            val me = client.get("/v1/auth/me") { bearerAuth(signedIn.field("access_token")) }
            assertEquals(
                """["pwd","otp"]""",
                ApiJson
                    .parseToJsonElement(me.bodyAsText())
                    .jsonObject
                    .getValue("amr")
                    .toString(),
            )
            assertEquals(HttpStatusCode.OK, client.get(Probe.ADMIN.path) { bearerAuth(signedIn.field("access_token")) }.status)

            assertEquals(HttpStatusCode.Unauthorized, client.mfa(first.field("mfa_token"), code).status, "a used MFA token")
            val reused = client.mfa(client.signIn(adminEmail).field("mfa_token"), code)
            assertEquals(HttpStatusCode.Unauthorized, reused.status, "a reused code")

            val expiring = client.signIn(adminEmail).field("mfa_token")
            clock.advance(5.minutes + 1.seconds)
            val laterCode = Totp.code(secret, Totp.step(clock.now.epochSecond))
            assertEquals(HttpStatusCode.Unauthorized, client.mfa(expiring, laterCode).status, "an expired MFA token")
            assertEquals(HttpStatusCode.OK, client.mfa(client.signIn(adminEmail).field("mfa_token"), laterCode).status)

            val actions = AuthFixtures.auditActions()
            assertEquals(2, actions.count { it == "auth.sign_in.succeeded" })
            assertEquals(1, actions.count { it == "auth.mfa.failed" })
        }
    }

    @Test
    fun `IT-AUTH-06 wrong TOTP codes count towards the lock`() =
        authApp {
            AuthFixtures.admin(adminEmail)
            repeat(
                10,
            ) { assertEquals(HttpStatusCode.Unauthorized, client.mfa(client.signIn(adminEmail).field("mfa_token"), "000000").status) }
            assertEquals(HttpStatusCode.Unauthorized, client.signIn(adminEmail).status, "locked: the password step fails too")
            assertTrue("auth.account.locked" in AuthFixtures.auditActions())
        }

    @Test
    fun `IT-AUTH-07 a refresh rotates, and reusing the old token ends the session`() =
        authApp {
            AuthFixtures.staff(AuthFixtures.restaurant(), email)
            val first = client.signIn(email).cookie(staffCookie)!!
            val rotated = client.refresh(first)
            assertEquals(HttpStatusCode.OK, rotated.status)
            assertEquals("staff", rotated.field("audience"))
            val second = rotated.cookie(staffCookie)!!
            assertNotEquals(first, second)
            val access = rotated.field("access_token")
            assertEquals(HttpStatusCode.OK, client.probe(access).status)

            val reuse = client.refresh(first)
            assertEquals(HttpStatusCode.Unauthorized, reuse.status)
            assertEquals("", reuse.cookie(staffCookie), "the cookie is cleared")
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(second).status, "the new token stopped working too")
            assertEquals(HttpStatusCode.Unauthorized, client.probe(access).status, "and so did the access token")
            assertTrue("auth.refresh.reuse_detected" in AuthFixtures.auditActions())
            assertEquals(
                listOf("refresh_reuse"),
                AuthFixtures.query("SELECT revoke_reason FROM staff_sessions") { it.getString(1) },
            )
        }

    @Test
    fun `IT-AUTH-08 a refresh token expires 12 h after its issue, a session 24 h after sign-in`() {
        val clock = MutableClock()
        authApp(clock) {
            AuthFixtures.staff(AuthFixtures.restaurant(), email)
            val idle = client.signIn(email).cookie(staffCookie)!!
            clock.advance(12.hours + 1.seconds)
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(idle).status, "unused for 12 h")

            var token = client.signIn(email).cookie(staffCookie)!!
            repeat(3) {
                clock.advance(7.hours)
                val response = client.refresh(token)
                assertEquals(HttpStatusCode.OK, response.status)
                token = response.cookie(staffCookie)!!
            }
            // 21 h in; the last token is capped at the session's end, 3 h from now.
            clock.advance(3.hours + 1.seconds)
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(token).status, "24 h after sign-in, even with use")
        }
    }

    @Test
    fun `IT-AUTH-12 the refresh cookie's attributes, one name per audience`() {
        val clock = MutableClock()
        authApp(clock) {
            AuthFixtures.staff(AuthFixtures.restaurant(), email)
            val header = client.signIn(email).setCookieHeader(staffCookie)!!
            val attributes = header.split("; ").drop(1).toSet()
            assertEquals(setOf("Max-Age=43200", "Path=${TestKeys.COOKIE_PATH}", "Secure", "HttpOnly", "SameSite=Strict"), attributes)

            val secret = Totp.newSecret(SecureRandom())
            AuthFixtures.admin(adminEmail, secret)
            val admin = client.mfa(client.signIn(adminEmail).field("mfa_token"), Totp.code(secret, Totp.step(clock.now.epochSecond)))
            assertNotNull(admin.setCookieHeader(adminCookie))
            assertEquals(null, admin.setCookieHeader(staffCookie))

            val failed = client.refresh("not-a-token")
            assertEquals(
                "$staffCookie=; Max-Age=0; Path=${TestKeys.COOKIE_PATH}; Secure; HttpOnly; SameSite=Strict",
                failed.setCookieHeader(staffCookie),
            )
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(null).status, "no cookie")
            assertEquals(HttpStatusCode.BadRequest, client.refresh("x", audience = "guest").status)
        }
    }

    @Test
    fun `a staff refresh cookie doesn't work as the admin one`() =
        authApp {
            AuthFixtures.staff(AuthFixtures.restaurant(), email)
            val token = client.signIn(email).cookie(staffCookie)!!
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(token, audience = "admin").status)
        }

    @Test
    fun `IT-AUTH-09 sign-out, a password change or reset, deactivation and a role change end access at once`() =
        authApp {
            val restaurant = AuthFixtures.restaurant()
            val staff = AuthFixtures.staff(restaurant, email)

            // Sign-out by cookie and by access token.
            val signedIn = client.signIn(email)
            val access = signedIn.field("access_token")
            val out =
                client.postJson("/v1/auth/sign-out", """{"audience": "staff"}""") {
                    header(HttpHeaders.Cookie, "$staffCookie=${signedIn.cookie(staffCookie)}")
                }
            assertEquals(HttpStatusCode.NoContent, out.status)
            assertEquals("", out.cookie(staffCookie))
            assertEquals(HttpStatusCode.Unauthorized, client.probe(access).status, "sign-out by cookie")
            val byToken = client.accessToken(email)
            client.postJson("/v1/auth/sign-out", """{"audience": "staff"}""") { bearerAuth(byToken) }
            assertEquals(HttpStatusCode.Unauthorized, client.probe(byToken).status, "sign-out by access token")
            assertEquals(
                HttpStatusCode.NoContent,
                client.postJson("/v1/auth/sign-out", """{"audience": "staff"}""").status,
                "nothing matched",
            )

            // Password change ends every session, this one included.
            val one = client.accessToken(email)
            val two = client.accessToken(email)
            val changed =
                client.postJson(
                    "/v1/auth/password",
                    """{"current_password": "${AuthFixtures.PASSWORD}", "new_password": "a brand new passphrase"}""",
                ) {
                    bearerAuth(one)
                }
            assertEquals(HttpStatusCode.NoContent, changed.status)
            assertEquals(HttpStatusCode.Unauthorized, client.probe(one).status)
            assertEquals(HttpStatusCode.Unauthorized, client.probe(two).status)
            assertEquals(HttpStatusCode.Unauthorized, client.signIn(email).status, "the old password")
            assertEquals(HttpStatusCode.OK, client.signIn(email, "a brand new passphrase").status)

            // A reset ends every session.
            val beforeReset = client.accessToken(email, "a brand new passphrase")
            val reset = AuthFixtures.link(staff, "password_reset")
            val completed =
                client.postJson(
                    "/v1/auth/password-resets/complete",
                    """{"token": "$reset", "password": "the reset passphrase"}""",
                )
            assertEquals(HttpStatusCode.NoContent, completed.status)
            assertEquals(HttpStatusCode.Unauthorized, client.probe(beforeReset).status)

            // Role change (the restaurants feature revokes the sessions) and deactivation (the session query checks it).
            val beforeRole = client.accessToken(email, "the reset passphrase")
            AuthFixtures.sql("UPDATE staff_accounts SET role = 'waiter' WHERE id = ?", staff.value)
            AuthFixtures.sql("UPDATE staff_sessions SET revoked_at = now(), revoke_reason = 'role_changed' WHERE staff_id = ?", staff.value)
            assertEquals(HttpStatusCode.Unauthorized, client.probe(beforeRole).status)
            val beforeDeactivation = client.accessToken(email, "the reset passphrase")
            AuthFixtures.sql("UPDATE staff_accounts SET status = 'deactivated', deactivated_at = now() WHERE id = ?", staff.value)
            assertEquals(HttpStatusCode.Unauthorized, client.probe(beforeDeactivation).status)

            val actions = AuthFixtures.auditActions()
            assertTrue(
                listOf("auth.sign_out", "auth.password.changed", "auth.password_reset.completed").all { it in actions },
                actions.toString(),
            )
        }

    @Test
    fun `a wrong current password is 401 and counts towards the lock`() =
        authApp {
            AuthFixtures.staff(AuthFixtures.restaurant(), email)
            val token = client.accessToken(email)
            val wrong =
                client.postJson(
                    "/v1/auth/password",
                    """{"current_password": "nope", "new_password": "a brand new passphrase"}""",
                ) { bearerAuth(token) }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
            assertEquals("invalid_credentials", wrong.field("code"))
            assertEquals(
                listOf(1),
                AuthFixtures.query("SELECT failed_count FROM staff_accounts") { it.getInt(1) },
            )
            val rejected =
                client.postJson(
                    "/v1/auth/password",
                    """{"current_password": "${AuthFixtures.PASSWORD}", "new_password": "lan"}""",
                ) { bearerAuth(token) }
            assertEquals("password_rejected", rejected.field("code"))
            assertFalse(AuthFixtures.auditActions().contains("auth.password.changed"))
        }
}
