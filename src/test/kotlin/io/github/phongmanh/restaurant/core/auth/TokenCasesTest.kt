package io.github.phongmanh.restaurant.core.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.phongmanh.restaurant.core.application.StaffRole
import io.github.phongmanh.restaurant.core.auth.crypto.AccessClaims
import io.github.phongmanh.restaurant.core.auth.crypto.AccessTokens
import io.github.phongmanh.restaurant.core.auth.crypto.Audience
import io.github.phongmanh.restaurant.core.auth.crypto.EcKeySet
import io.github.phongmanh.restaurant.core.auth.persistence.CallerRepository
import io.github.phongmanh.restaurant.core.auth.persistence.MemberFilter
import io.github.phongmanh.restaurant.core.auth.persistence.StaffAccountRepository
import io.github.phongmanh.restaurant.core.auth.persistence.StaffMemberRepository
import io.github.phongmanh.restaurant.core.domain.StaffSessionId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.ApiJson
import io.github.phongmanh.restaurant.core.http.Problem
import io.github.phongmanh.restaurant.core.persistence.DatabasePools
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.MutableClock
import io.github.phongmanh.restaurant.support.Probe
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.TestKeys
import io.github.phongmanh.restaurant.support.accessToken
import io.github.phongmanh.restaurant.support.authApp
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** IT-AUTH-05 (S-4's token cases), suspension (restaurants §6) and IT-CORE-07. */
class TokenCasesTest {
    private val email = "lan@pho.example"
    private val adminEmail = "ops@platform.example"

    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private suspend fun HttpClient.probe(
        path: String,
        token: String?,
    ): HttpResponse = get(path) { token?.let { bearerAuth(it) } }

    private suspend fun HttpResponse.code(): String = ApiJson.decodeFromString<Problem>(bodyAsText()).code

    /** A token signed by [key] with the given claims, for the cases sign-in can't make on purpose. */
    private fun forge(
        staffId: String,
        sessionId: String,
        audience: String = "staff",
        amr: List<String> = listOf("pwd"),
        issuer: String = TestKeys.ISSUER,
        issuedAt: Instant = Instant.now(),
        kid: String = "staff-2",
        key: java.security.KeyPair = TestKeys.current,
    ): String =
        JWT
            .create()
            .withKeyId(kid)
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(staffId)
            .withClaim("sid", sessionId)
            .withClaim("amr", amr)
            .withIssuedAt(Date.from(issuedAt))
            .withExpiresAt(Date.from(issuedAt.plusSeconds(900)))
            .sign(Algorithm.ECDSA256(key.public as ECPublicKey, key.private as ECPrivateKey))

    private fun claimsOf(token: String) = JWT.decode(token).let { it.subject to it.getClaim("sid").asString() }

    @Test
    fun `IT-AUTH-05 every bad token is 401, and a staff token on an admin route is 404`() =
        authApp {
            AuthFixtures.staff(AuthFixtures.restaurant(), email)
            val valid = client.accessToken(email)
            val (sub, sid) = claimsOf(valid)
            val b64 = Base64.getUrlEncoder().withoutPadding()
            val (header, payload, _) = valid.split('.')

            assertEquals(HttpStatusCode.OK, client.probe(Probe.STAFF.path, valid).status)
            assertEquals(
                HttpStatusCode.OK,
                client.probe(Probe.STAFF.path, forge(sub, sid, kid = "staff-1", key = TestKeys.previous)).status,
                "previous key",
            )

            val refused =
                mapOf(
                    "no token" to null,
                    "not a JWT" to "not-a-jwt",
                    "expired" to forge(sub, sid, issuedAt = Instant.now().minusSeconds(16 * 60)),
                    "forged: another key" to forge(sub, sid, key = TestKeys.ecKeyPair()),
                    "unknown kid" to forge(sub, sid, kid = "nobody"),
                    "wrong issuer" to forge(sub, sid, issuer = "https://someone-else.example"),
                    "payload changed" to
                        "$header.${b64.encodeToString(
                            """{"sub":"$sub","sid":"$sid","aud":"admin"}""".toByteArray(),
                        )}.${valid.split('.')[2]}",
                    "alg none" to "${b64.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())}.$payload.",
                    "alg HS256" to
                        JWT
                            .create()
                            .withIssuer(
                                TestKeys.ISSUER,
                            ).withAudience("staff")
                            .withSubject(sub)
                            .withClaim("sid", sid)
                            .sign(Algorithm.HMAC256("guess")),
                    "unknown session" to forge(sub, UuidV7.next().toString()),
                    "admin token on a staff route" to forge(sub, sid, audience = "admin", amr = listOf("pwd", "otp")),
                )
            for ((case, token) in refused) {
                val response = client.probe(Probe.STAFF.path, token)
                assertEquals(HttpStatusCode.Unauthorized, response.status, case)
                assertEquals("unauthenticated", response.code(), case)
                assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate], case)
            }

            val onAdmin = client.probe(Probe.ADMIN.path, valid)
            assertEquals(HttpStatusCode.NotFound, onAdmin.status, "a staff token on an admin route")
            assertEquals("not_found", onAdmin.code())
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.probe(Probe.GUEST.path, valid).status,
                "a staff token is no guest token",
            )
        }

    @Test
    fun `IT-AUTH-05 an admin token needs otp and a platform admin`() =
        authApp {
            val admin = AuthFixtures.admin(adminEmail)
            val session = UuidV7.next()
            AuthFixtures.sql(
                "INSERT INTO staff_sessions (id, staff_id, audience, amr, started_at, expires_at) " +
                    "VALUES (?, ?, 'admin', '{pwd,otp}', now(), now() + interval '1 day')",
                session,
                admin.value,
            )
            val good = forge(admin.toString(), session.toString(), audience = "admin", amr = listOf("pwd", "otp"))
            assertEquals(HttpStatusCode.OK, client.probe(Probe.ADMIN.path, good).status)
            assertTrue("Admin" in client.probe(Probe.ADMIN.path, good).bodyAsText())

            val withoutOtp = forge(admin.toString(), session.toString(), audience = "admin")
            assertEquals(HttpStatusCode.Unauthorized, client.probe(Probe.ADMIN.path, withoutOtp).status, "admin token without otp")

            // A staff member's session behind an admin-audience token: the database says it isn't an admin.
            AuthFixtures.staff(AuthFixtures.restaurant(), email)
            val (sub, sid) = claimsOf(client.accessToken(email))
            val staffAsAdmin = forge(sub, sid, audience = "admin", amr = listOf("pwd", "otp"))
            assertEquals(HttpStatusCode.Unauthorized, client.probe(Probe.ADMIN.path, staffAsAdmin).status)
        }

    @Test
    fun `a suspended restaurant's staff get 403 except where the registry allows it`() =
        authApp {
            val restaurant = AuthFixtures.restaurant()
            AuthFixtures.staff(restaurant, email)
            val token = client.accessToken(email)
            AuthFixtures.suspend(restaurant)

            val blocked = client.probe(Probe.STAFF.path, token)
            assertEquals(HttpStatusCode.Forbidden, blocked.status)
            assertEquals("restaurant_suspended", blocked.code())
            assertEquals(HttpStatusCode.OK, client.probe(Probe.STAFF_WHEN_SUSPENDED.path, token).status)
            assertEquals(HttpStatusCode.OK, client.probe("/v1/auth/me", token).status, "every auth route is allowed when suspended")
        }

    @Test
    fun `the access token expires after 15 minutes plus 30 s leeway`() {
        val clock = MutableClock()
        authApp(clock) {
            AuthFixtures.staff(AuthFixtures.restaurant(), email)
            val token = client.accessToken(email)
            clock.advance(15.minutes)
            assertEquals(HttpStatusCode.OK, client.probe(Probe.STAFF.path, token).status, "within the leeway")
            clock.advance(1.minutes)
            assertEquals(HttpStatusCode.Unauthorized, client.probe(Probe.STAFF.path, token).status)
        }
    }

    @Test
    fun `AccessTokens round-trips its claims and refuses another audience`() {
        val tokens = AccessTokens(EcKeySet.parse(TestKeys.staffKeysJson, "STAFF_JWT_KEYS"), TestKeys.ISSUER, Clock.systemUTC())
        val claims = AccessClaims(UuidV7.next(), UuidV7.next(), Audience.ADMIN, listOf("pwd", "otp"))
        val token = tokens.sign(claims)
        assertEquals(claims, tokens.verify(token, setOf(Audience.ADMIN)))
        assertNull(tokens.verify(token, setOf(Audience.STAFF)))
        assertEquals("staff-2", JWT.decode(token).keyId)
        assertEquals("ES256", JWT.decode(token).algorithm)
    }

    @Test
    fun `IT-CORE-07 the reads a restaurant's staff can trigger filter by their restaurant`() {
        val a = AuthFixtures.restaurant("A")
        val b = AuthFixtures.restaurant("B")
        val inB = AuthFixtures.staff(b, "b@pho.example")
        val sessionInB = UuidV7.next()
        AuthFixtures.sql(
            "INSERT INTO staff_sessions (id, staff_id, audience, amr, started_at, expires_at) " +
                "VALUES (?, ?, 'staff', '{pwd}', now(), now() + interval '1 day')",
            sessionInB,
            inB.value,
        )
        DatabasePools(TestDatabase.settings).use { pools ->
            val transactor = ExposedTransactor(pools)
            val accounts = StaffAccountRepository()
            val callers = CallerRepository()
            runBlocking {
                transactor.inTransaction(a) {
                    assertNull(accounts.findInRestaurant(this, a, inB), "staff of A can't read an account of B")
                    assertNull(callers.me(this, inB, StaffSessionId(sessionInB), a))
                    assertNull(callers.me(this, inB, StaffSessionId(sessionInB), null), "nor as if it were an admin")
                }
                // Staff management (restaurants §7): a restaurant's managers reach only its accounts.
                val members = StaffMemberRepository()
                transactor.inTransaction(a) {
                    assertNull(members.find(this, a, inB))
                    assertTrue(members.list(this, a, MemberFilter(), null, 10).none { it.id == inB })
                    assertEquals(0, members.counts(this, a).seats)
                }
                transactor.inTransaction(b) {
                    assertNotNull(members.find(this, b, inB))
                    assertNotNull(accounts.findInRestaurant(this, b, inB))
                    assertEquals(StaffRole.MANAGER, callers.me(this, inB, StaffSessionId(sessionInB), b)?.role)
                    assertNull(accounts.findPlatformAdmin(this, inB), "a restaurant's staff member is never found as an admin")
                }
            }
        }
    }
}
