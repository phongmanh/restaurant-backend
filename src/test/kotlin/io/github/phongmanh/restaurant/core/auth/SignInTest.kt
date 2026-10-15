package io.github.phongmanh.restaurant.core.auth

import io.github.phongmanh.restaurant.core.auth.crypto.Argon2Hasher
import io.github.phongmanh.restaurant.core.auth.crypto.Argon2Settings
import io.github.phongmanh.restaurant.core.auth.crypto.PasswordHasher
import io.github.phongmanh.restaurant.core.http.ApiJson
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.MutableClock
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.TestKeys
import io.github.phongmanh.restaurant.support.accessToken
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.cookie
import io.github.phongmanh.restaurant.support.field
import io.github.phongmanh.restaurant.support.postJson
import io.github.phongmanh.restaurant.support.signIn
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** IT-AUTH-01 to IT-AUTH-04 (core-and-auth §8.1, §15). */
class SignInTest {
    private val email = "lan@pho.example"

    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    @Test
    fun `IT-AUTH-01 the right password signs in, and the token names the caller`() =
        authApp {
            val restaurant = AuthFixtures.restaurant()
            val staff = AuthFixtures.staff(restaurant, email, role = "waiter")

            val response = client.signIn("  LAN@pho.example ")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = ApiJson.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("false", body.getValue("mfa_required").jsonPrimitive.content)
            assertEquals("Bearer", body.getValue("token_type").jsonPrimitive.content)
            assertEquals("900", body.getValue("expires_in").jsonPrimitive.content)
            assertEquals("staff", body.getValue("audience").jsonPrimitive.content)
            assertNotNull(response.cookie("__Secure-rt-staff"))
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])

            val me = client.get("/v1/auth/me") { bearerAuth(body.getValue("access_token").jsonPrimitive.content) }
            assertEquals(HttpStatusCode.OK, me.status)
            val meBody = ApiJson.parseToJsonElement(me.bodyAsText()).jsonObject
            assertEquals(staff.toString(), meBody.getValue("staff_id").jsonPrimitive.content)
            assertEquals("waiter", meBody.getValue("role").jsonPrimitive.content)
            assertEquals(email, meBody.getValue("email").jsonPrimitive.content)
            assertEquals(
                restaurant.toString(),
                meBody
                    .getValue("restaurant")
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content,
            )
            assertEquals("""["pwd"]""", meBody.getValue("amr").toString())
            assertEquals("false", meBody.getValue("platform_admin").jsonPrimitive.content)
            assertEquals(listOf("auth.sign_in.succeeded"), AuthFixtures.auditActions())
        }

    @Test
    fun `IT-AUTH-02 every failure gets the same 401 and runs the hash once`() {
        val hashes = AtomicInteger()
        val counting = CountingHasher(Argon2Hasher(TestKeys.argon2), hashes)
        authApp(overrides = listOf(module { single<PasswordHasher> { counting } })) {
            val restaurant = AuthFixtures.restaurant()
            AuthFixtures.staff(restaurant, email)
            AuthFixtures.staff(restaurant, "gone@pho.example", status = "deactivated")
            AuthFixtures.staff(restaurant, "new@pho.example", status = "invited", password = null)
            AuthFixtures.staff(restaurant, "locked@pho.example")
            repeat(10) { client.signIn("locked@pho.example", "wrong password") }

            val cases =
                mapOf(
                    "unknown email" to client.signIn("nobody@pho.example"),
                    "wrong password" to client.signIn(email, "wrong password"),
                    "deactivated" to client.signIn("gone@pho.example"),
                    "invited" to client.signIn("new@pho.example"),
                    "locked, right password" to client.signIn("locked@pho.example"),
                )
            val bodies =
                cases.mapValues { (_, r) ->
                    assertEquals(HttpStatusCode.Unauthorized, r.status)
                    JsonObject(ApiJson.parseToJsonElement(r.bodyAsText()).jsonObject - "request_id")
                }
            assertEquals(1, bodies.values.toSet().size, "identical bodies: $bodies")
            assertEquals(
                "invalid_credentials",
                bodies.values
                    .first()
                    .getValue("code")
                    .jsonPrimitive.content,
            )
            // The 10 locking tries plus the 5 cases: each ran Argon2id exactly once.
            assertEquals(15, hashes.get())
        }
    }

    @Test
    fun `IT-AUTH-02 response times of the failures are within one band`() {
        // Production parameters here, so the hash dominates as it does in production.
        val production = Argon2Hasher(Argon2Settings(19_456, 2, 1, 4, 2.seconds))
        authApp(overrides = listOf(module { single<PasswordHasher> { production } })) {
            val restaurant = AuthFixtures.restaurant()
            // Stored with the same parameters as the dummy hash, as in production.
            val hash = production.hash(AuthFixtures.PASSWORD)
            for (staff in listOf(
                AuthFixtures.staff(restaurant, email),
                AuthFixtures.staff(restaurant, "gone@pho.example", status = "deactivated"),
            )) {
                AuthFixtures.sql("UPDATE staff_accounts SET password_hash = ? WHERE id = ?", hash, staff.value)
            }
            client.signIn(email, "warm up the JIT")
            val emails = listOf("nobody@pho.example", email, "gone@pho.example")
            val medians =
                emails.map { e ->
                    (1..5)
                        .map {
                            val mark = TimeSource.Monotonic.markNow()
                            client.signIn(e, "wrong password")
                            mark.elapsedNow()
                        }.sorted()[2]
                }
            assertTrue(medians.max() < medians.min() * 3, "medians $medians")
        }
    }

    @Test
    fun `IT-AUTH-03 ten failures lock the account for 15 minutes, and success resets the count`() {
        val clock = MutableClock()
        authApp(clock) {
            AuthFixtures.staff(AuthFixtures.restaurant(), email)
            repeat(9) { client.signIn(email, "wrong password") }
            assertEquals(HttpStatusCode.OK, client.signIn(email).status, "9 failures don't lock")
            repeat(9) { client.signIn(email, "wrong password") }
            assertEquals(HttpStatusCode.OK, client.signIn(email).status, "the success reset the count")

            repeat(10) { client.signIn(email, "wrong password") }
            assertEquals(HttpStatusCode.Unauthorized, client.signIn(email).status, "locked: the right password is refused")
            assertEquals(1, AuthFixtures.auditActions().count { it == "auth.account.locked" })

            clock.advance(14.minutes)
            repeat(5) { client.signIn(email, "wrong password") }
            clock.advance(1.minutes + 1.seconds)
            assertEquals(HttpStatusCode.OK, client.signIn(email).status, "tries while locked didn't extend the lock")

            val reasons =
                AuthFixtures.query("SELECT details->>'reason' FROM audit_events WHERE action = 'auth.sign_in.failed'") { it.getString(1) }
            assertTrue("locked" in reasons && "bad_password" in reasons, reasons.toString())
        }
    }

    @Test
    fun `IT-AUTH-04 a lock doesn't stop an existing refresh token`() =
        authApp {
            AuthFixtures.staff(AuthFixtures.restaurant(), email)
            val refresh = client.signIn(email).cookie("__Secure-rt-staff")!!
            repeat(10) { client.signIn(email, "wrong password") }
            assertEquals(HttpStatusCode.Unauthorized, client.signIn(email).status)

            val refreshed =
                client.postJson(
                    "/v1/auth/refresh",
                    """{"audience": "staff"}""",
                ) { header(HttpHeaders.Cookie, "__Secure-rt-staff=$refresh") }
            assertEquals(HttpStatusCode.OK, refreshed.status)
        }

    @Test
    fun `a stored hash with older parameters is upgraded at sign-in`() =
        authApp {
            val restaurant = AuthFixtures.restaurant()
            val old = Argon2Hasher(TestKeys.argon2.copy(iterations = 3)).hash(AuthFixtures.PASSWORD)
            val staff = AuthFixtures.staff(restaurant, email)
            AuthFixtures.sql("UPDATE staff_accounts SET password_hash = ? WHERE id = ?", old, staff.value)
            client.accessToken(email)
            val stored =
                AuthFixtures
                    .query(
                        "SELECT password_hash FROM staff_accounts WHERE id = ?",
                        staff.value,
                    ) { it.getString(1) }
                    .single()
            assertTrue(",t=1," in stored, stored)
            assertEquals(HttpStatusCode.OK, client.signIn(email).status)
        }

    @Test
    fun `a malformed sign-in is 400`() =
        authApp {
            assertEquals(HttpStatusCode.BadRequest, client.postJson("/v1/auth/sign-in", """{"email": "x"}""").status)
            val noAt = client.postJson("/v1/auth/sign-in", """{"email": "nobody", "password": "x"}""")
            assertEquals(HttpStatusCode.BadRequest, noAt.status)
            assertEquals("validation_failed", noAt.field("code"))
        }

    private class CountingHasher(
        private val inner: PasswordHasher,
        private val count: AtomicInteger,
    ) : PasswordHasher by inner {
        override fun verify(
            password: String,
            phc: String,
        ): Boolean = inner.verify(password, phc).also { count.incrementAndGet() }
    }
}
