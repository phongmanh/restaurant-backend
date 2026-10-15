package io.github.phongmanh.restaurant.core.auth

import io.github.phongmanh.restaurant.core.auth.crypto.Totp
import io.github.phongmanh.restaurant.core.http.ApiJson
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.MutableClock
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.field
import io.github.phongmanh.restaurant.support.postJson
import io.github.phongmanh.restaurant.support.signIn
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** IT-AUTH-10 and IT-AUTH-11 (core-and-auth §8.5, §8.6). */
class LinksTest {
    private val email = "minh@pho.example"

    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private suspend fun HttpClient.inspect(token: String) = postJson("/v1/auth/links/inspect", """{"token": "$token"}""")

    private suspend fun HttpClient.accept(
        token: String,
        password: String,
        code: String? = null,
    ) = postJson(
        "/v1/auth/invitations/accept",
        """{"token": "$token", "password": ${ApiJson.encodeToString(password)}${code?.let { ", \"totp_code\": \"$it\"" } ?: ""}}""",
    )

    private suspend fun HttpResponse.rejection(): String =
        ApiJson
            .parseToJsonElement(bodyAsText())
            .jsonObject
            .getValue("errors")
            .jsonArray[0]
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content

    @Test
    fun `IT-AUTH-10 a staff invitation is inspected, accepted, then used to sign in`() =
        authApp {
            val restaurant = AuthFixtures.restaurant("Phở Thìn")
            val manager = AuthFixtures.staff(restaurant, "boss@pho.example")
            val invited = AuthFixtures.staff(restaurant, email, role = "kitchen", status = "invited", password = null)
            val token = AuthFixtures.link(invited, "invitation", createdBy = manager)

            val inspected = client.inspect(token)
            assertEquals(HttpStatusCode.OK, inspected.status)
            val details = ApiJson.parseToJsonElement(inspected.bodyAsText()).jsonObject
            assertEquals("invitation", details.getValue("kind").jsonPrimitive.content)
            assertEquals(email, details.getValue("email").jsonPrimitive.content)
            assertEquals("Phở Thìn", details.getValue("restaurant_name").jsonPrimitive.content)
            assertEquals("kitchen", details.getValue("role").jsonPrimitive.content)
            assertEquals("false", details.getValue("totp_enrolment_required").jsonPrimitive.content)

            assertEquals(HttpStatusCode.NoContent, client.accept(token, "a long kitchen passphrase").status)
            assertEquals(HttpStatusCode.OK, client.signIn(email, "a long kitchen passphrase").status)
            val used = client.inspect(token)
            assertEquals(HttpStatusCode.NotFound, used.status)
            assertEquals("link_invalid", used.field("code"))
            assertEquals(HttpStatusCode.NotFound, client.accept(token, "a long kitchen passphrase").status, "a used link")
            assertTrue("auth.invitation.accepted" in AuthFixtures.auditActions())
        }

    @Test
    fun `IT-AUTH-10 replaced, expired and unknown links are all link_invalid`() =
        authApp {
            val restaurant = AuthFixtures.restaurant()
            val invited = AuthFixtures.staff(restaurant, email, status = "invited", password = null)
            val replaced = AuthFixtures.link(invited, "invitation")
            AuthFixtures.sql("UPDATE staff_link_tokens SET superseded_at = now()")
            val expired = AuthFixtures.link(invited, "invitation", expiresAt = Instant.now().minusSeconds(1))
            val reset = AuthFixtures.link(invited, "password_reset")
            for ((case, token) in mapOf("replaced" to replaced, "expired" to expired, "unknown" to "no-such-link")) {
                val response = client.inspect(token)
                assertEquals(HttpStatusCode.NotFound, response.status, case)
                assertEquals("link_invalid", response.field("code"), case)
            }
            assertEquals(HttpStatusCode.NotFound, client.accept(reset, "a long passphrase").status, "a reset link isn't an invitation")
        }

    @Test
    fun `IT-AUTH-10 an admin enrols TOTP in two calls, and a wrong code keeps the link`() {
        val clock = MutableClock()
        authApp(clock) {
            val inviter = AuthFixtures.admin("first@platform.example")
            val admin = AuthFixtures.admin("second@platform.example", totpSecret = null, status = "invited", password = null)
            val token = AuthFixtures.link(admin, "invitation", createdBy = inviter)
            assertEquals("true", client.inspect(token).field("totp_enrolment_required"))

            val setup = client.accept(token, "an admin passphrase")
            assertEquals(HttpStatusCode.OK, setup.status)
            val totp =
                ApiJson
                    .parseToJsonElement(setup.bodyAsText())
                    .jsonObject
                    .getValue("totp_setup")
                    .jsonObject
            val secretBase32 = totp.getValue("secret").jsonPrimitive.content
            assertTrue(
                totp
                    .getValue("otpauth_uri")
                    .jsonPrimitive.content
                    .startsWith("otpauth://totp/"),
            )

            val wrong = client.accept(token, "an admin passphrase", "000000")
            assertEquals(HttpStatusCode.BadRequest, wrong.status)
            assertEquals("validation_failed", wrong.field("code"))
            assertEquals(HttpStatusCode.OK, client.inspect(token).status, "the link stays valid")

            val secret = decodeBase32(secretBase32)
            val code = Totp.code(secret, Totp.step(clock.now.epochSecond))
            assertEquals(HttpStatusCode.NoContent, client.accept(token, "an admin passphrase", code).status)
            assertEquals(HttpStatusCode.NotFound, client.inspect(token).status)

            // Sign in: the enrolment's code can't be used again, the next step's can.
            val mfaToken = client.signIn("second@platform.example", "an admin passphrase").field("mfa_token")
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.postJson("/v1/auth/mfa", """{"mfa_token": "$mfaToken", "code": "$code"}""").status,
            )
            clock.advance(30.seconds)
            val again = client.signIn("second@platform.example", "an admin passphrase").field("mfa_token")
            val next = Totp.code(secret, Totp.step(clock.now.epochSecond))
            assertEquals(HttpStatusCode.OK, client.postJson("/v1/auth/mfa", """{"mfa_token": "$again", "code": "$next"}""").status)
            assertTrue("auth.totp.enrolled" in AuthFixtures.auditActions())
        }
    }

    @Test
    fun `IT-AUTH-11 password rules`() =
        authApp {
            val invited = AuthFixtures.staff(AuthFixtures.restaurant(), email, status = "invited", password = null)
            val token = AuthFixtures.link(invited, "invitation")
            val cases =
                mapOf<String, Pair<String, String>>(
                    "7 characters" to ("abcdefg" to "too_short"),
                    "65 characters" to ("a".repeat(65) to "too_long"),
                    "common" to ("password123" to "too_common"),
                    "common, other case" to ("PASSWORD123" to "too_common"),
                    "the email" to (email to "matches_email"),
                )
            for ((case, input) in cases) {
                val (password, expected) = input
                val response = client.accept(token, password)
                assertEquals(HttpStatusCode.BadRequest, response.status, case)
                assertEquals("password_rejected", response.field("code"), case)
                assertEquals(expected, response.rejection(), case)
            }
        }

    @Test
    fun `IT-AUTH-11 the local part alone is refused, and 64 characters with spaces and emoji work`() =
        authApp {
            val restaurant = AuthFixtures.restaurant()
            val invited = AuthFixtures.staff(restaurant, "longname@pho.example", status = "invited", password = null)
            val token = AuthFixtures.link(invited, "invitation")
            assertEquals("matches_email", client.accept(token, "LongName").rejection())

            // 64 code points: spaces and emoji (two UTF-16 units each) count once each.
            val password = "phở bò 🍜🍜 " + "x".repeat(54)
            assertEquals(64, password.codePointCount(0, password.length))
            assertFalse(password.length == 64, "the emoji make it longer in UTF-16")
            assertEquals(HttpStatusCode.NoContent, client.accept(token, password).status)
            assertEquals(HttpStatusCode.OK, client.signIn("longname@pho.example", password).status)
        }

    private fun decodeBase32(text: String): ByteArray {
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
}
