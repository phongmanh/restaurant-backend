package spike

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every sign-in and token case of architecture §7, through the full Ktor pipeline. */
class AuthFlowTest {
    private val clock = MutableClock(Instant.now())
    private val keys = SpikeKeys.generate()
    private val tokens = Tokens(keys, "https://test.example", clock)
    private val auth = AuthService(
        accounts = Seeds.accounts(),
        hashers = listOf(BouncyCastleArgon2(), Password4jArgon2()).associateBy { it.name },
        gate = HashGate(4),
        tokens = tokens,
        clock = clock,
        dummyPhc = Seeds.DUMMY_PHC,
    )
    private val adminSecret = Seeds.ADMIN_TOTP_SECRET_HEX.hexToByteArray()

    private fun spike(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { spikeModule(InstanceStats(), auth, tokens) { null } }
        block()
    }

    @Test
    fun `a waiter signs in with either library and reaches the staff route`() = spike {
        for (lib in HASHERS) {
            val response = client.signIn("waiter@a.test", Seeds.PASSWORD, lib)
            assertEquals(HttpStatusCode.OK, response.status, lib)
            val body = response.json()
            assertEquals(lib, body.obj("spike")["hasher"]!!.jsonPrimitive.content)
            val me = client.get("/v1/staff/me") { bearerAuth(body.str("access_token")) }
            assertEquals(HttpStatusCode.OK, me.status)
            assertTrue(me.bodyAsText().contains("\"role\":\"waiter\""))
        }
    }

    @Test
    fun `wrong password, unknown email and inactive account get the same answer, each after a hash`() = spike {
        for ((email, password) in listOf("waiter@a.test" to "wrong", "nobody@a.test" to Seeds.PASSWORD, "former@a.test" to Seeds.PASSWORD)) {
            val response = client.signIn(email, password)
            assertEquals(HttpStatusCode.Unauthorized, response.status, email)
            val body = response.json()
            assertEquals("invalid_credentials", body.str("error"), email)
            assertTrue(body.obj("spike")["hashMs"]!!.jsonPrimitive.content.toDouble() > 1.0, "$email: no hash ran")
        }
    }

    @Test
    fun `ten failures lock the account for 15 minutes, even with the right password`() = spike {
        repeat(AuthService.MAX_FAILURES) { client.signIn("waiter@a.test", "wrong-$it") }
        assertEquals(HttpStatusCode.Unauthorized, client.signIn("waiter@a.test", Seeds.PASSWORD).status)
        clock.advance(AuthService.LOCK_DURATION.plusSeconds(1))
        assertEquals(HttpStatusCode.OK, client.signIn("waiter@a.test", Seeds.PASSWORD).status)
    }

    @Test
    fun `an admin needs a TOTP code, and a code works once`() = spike {
        val first = client.signIn("admin@platform.test", Seeds.PASSWORD).json()
        assertEquals("true", first["mfa_required"]!!.jsonPrimitive.content)
        val mfaToken = first.str("mfa_token")

        // The MFA token isn't an access token.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/me") { bearerAuth(mfaToken) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.mfa(mfaToken, "000000").status)

        val second = client.signIn("admin@platform.test", Seeds.PASSWORD).json().str("mfa_token")
        val code = Totp.code(adminSecret, Totp.step(clock.instant().epochSecond))
        val done = client.mfa(second, code)
        assertEquals(HttpStatusCode.OK, done.status)
        val adminToken = done.json().str("access_token")
        val me = client.get("/v1/admin/me") { bearerAuth(adminToken) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertTrue(me.bodyAsText().contains("\"otp\""))

        // The same MFA token again, and the same code with a fresh MFA token: both refused.
        assertEquals(HttpStatusCode.Unauthorized, client.mfa(second, code).status)
        val third = client.signIn("admin@platform.test", Seeds.PASSWORD).json().str("mfa_token")
        assertEquals(HttpStatusCode.Unauthorized, client.mfa(third, code).status)

        // An admin token isn't a staff token.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/staff/me") { bearerAuth(adminToken) }.status)
    }

    @Test
    fun `refused tokens`() = spike {
        val cases = client.get("/spike/test-tokens").json()
        fun case(name: String) = cases.str(name)
        val staff = client.signIn("waiter@a.test", Seeds.PASSWORD).json().str("access_token")

        val expectations = listOf(
            Triple("/v1/staff/me", case("expired"), HttpStatusCode.Unauthorized),
            Triple("/v1/staff/me", case("guestKey"), HttpStatusCode.Unauthorized),
            Triple("/v1/staff/me", case("wrongIssuer"), HttpStatusCode.Unauthorized),
            Triple("/v1/staff/me", case("mfaTokenAsAccess"), HttpStatusCode.Unauthorized),
            Triple("/v1/staff/me", case("previousKey"), HttpStatusCode.OK),
            Triple("/v1/admin/me", case("staffValid"), HttpStatusCode.Unauthorized),
            Triple("/v1/admin/me", case("adminWithoutOtp"), HttpStatusCode.Forbidden),
            Triple("/v1/staff/me", forgeSubject(staff), HttpStatusCode.Unauthorized),
            Triple("/v1/staff/me", algNone(staff), HttpStatusCode.Unauthorized),
            Triple("/v1/staff/me", "not-a-jwt", HttpStatusCode.Unauthorized),
        )
        for ((path, token, expected) in expectations) {
            assertEquals(expected, client.get(path) { bearerAuth(token) }.status, "$path ${token.take(40)}")
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/staff/me").status)
    }

    @Test
    fun `refresh rotates, and reusing an old refresh token revokes the family`() = spike {
        val first = client.signIn("waiter@a.test", Seeds.PASSWORD).json().str("refresh_token")
        val second = client.refresh(first)
        assertEquals(HttpStatusCode.OK, second.status)
        val secondToken = second.json().str("refresh_token")

        assertEquals(HttpStatusCode.Unauthorized, client.refresh(first).status) // reuse → family revoked
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(secondToken).status)
    }

    @Test
    fun `sign-out revokes the refresh token, and refresh tokens expire after 12 hours`() = spike {
        val a = client.signIn("waiter@a.test", Seeds.PASSWORD).json().str("refresh_token")
        client.post("/v1/auth/sign-out") { json(buildJsonObject { put("refresh_token", a) }) }
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(a).status)

        val b = client.signIn("waiter@a.test", Seeds.PASSWORD).json().str("refresh_token")
        clock.advance(AuthService.REFRESH_LIFETIME.plusSeconds(1))
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(b).status)
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun HttpClient.signIn(email: String, password: String, lib: String = "bc") =
        post("/v1/auth/sign-in?lib=$lib") { json(buildJsonObject { put("email", email); put("password", password) }) }

    private suspend fun HttpClient.mfa(mfaToken: String, code: String) =
        post("/v1/auth/mfa") { json(buildJsonObject { put("mfa_token", mfaToken); put("code", code) }) }

    private suspend fun HttpClient.refresh(token: String) =
        post("/v1/auth/refresh") { json(buildJsonObject { put("refresh_token", token) }) }

    private fun io.ktor.client.request.HttpRequestBuilder.json(body: JsonObject) {
        contentType(ContentType.Application.Json)
        setBody(body.toString())
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

    /** Same header and signature, another subject: the signature no longer matches. */
    private fun forgeSubject(token: String): String {
        val (header, payload, signature) = token.split('.')
        val decoded = Base64.getUrlDecoder().decode(payload).decodeToString().replace(Seeds.WAITER_ID, Seeds.INACTIVE_ID)
        return "$header.${Base64.getUrlEncoder().withoutPadding().encodeToString(decoded.toByteArray())}.$signature"
    }

    /** The classic `alg: none` attack: an unsigned token. */
    private fun algNone(token: String): String {
        val payload = token.split('.')[1]
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        return "$header.$payload."
    }
}

class MutableClock(private var now: Instant) : Clock() {
    fun advance(by: Duration) {
        now = now.plus(by)
    }

    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}
