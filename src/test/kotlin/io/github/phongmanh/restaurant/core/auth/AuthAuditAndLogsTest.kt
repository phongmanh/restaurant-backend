package io.github.phongmanh.restaurant.core.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import io.github.phongmanh.restaurant.core.application.AuditChains
import io.github.phongmanh.restaurant.core.auth.crypto.Totp
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.persistence.AuditRepository
import io.github.phongmanh.restaurant.core.persistence.DatabasePools
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.MutableClock
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.cookie
import io.github.phongmanh.restaurant.support.field
import io.github.phongmanh.restaurant.support.postJson
import io.github.phongmanh.restaurant.support.signIn
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** The auth cases of IT-CORE-05 (audit) and IT-CORE-09 (logs). */
class AuthAuditAndLogsTest {
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeTest
    fun setUp() {
        TestDatabase.truncate()
        appender.start()
        root.addAppender(appender)
    }

    @AfterTest
    fun tearDown() {
        root.detachAppender(appender)
    }

    @Test
    fun `IT-CORE-05 each auth action writes its entry, and every chain verifies`() {
        val clock = MutableClock()
        val secret = Totp.newSecret(SecureRandom())
        val restaurant = AuthFixtures.restaurant()
        authApp(clock) {
            AuthFixtures.staff(restaurant, "lan@pho.example")
            AuthFixtures.admin("ops@platform.example", secret)
            everyAuthAction(client, clock, secret)

            val byChain =
                AuthFixtures
                    .query("SELECT restaurant_id, action FROM audit_events ORDER BY chain_seq") {
                        (it.getString(1) ?: "platform") to
                            it.getString(2)
                    }.groupBy({ it.first }, { it.second })
            assertEquals(
                setOf(
                    "auth.sign_in.failed",
                    "auth.account.locked",
                    "auth.sign_in.succeeded",
                    "auth.refresh.reuse_detected",
                    "auth.password.changed",
                    "auth.sign_out",
                ),
                byChain.getValue(restaurant.toString()).toSet(),
            )
            assertEquals(listOf("auth.sign_in.failed", "auth.mfa.failed", "auth.sign_in.succeeded"), byChain.getValue("platform"))
            val unknown =
                AuthFixtures
                    .query(
                        "SELECT details FROM audit_events WHERE restaurant_id IS NULL ORDER BY chain_seq LIMIT 1",
                    ) { it.getString(1) }
                    .single()
            assertTrue("\"email_hmac\"" in unknown && "nobody" !in unknown, unknown)
        }
        DatabasePools(TestDatabase.settings).use { pools ->
            val transactor = ExposedTransactor(pools)
            val store = AuditRepository()
            for (chain in listOf<RestaurantId?>(null, restaurant)) {
                val records = runBlocking { transactor.platformTransaction("test") { store.read(this, chain, 0, 1000) } }
                assertTrue(records.isNotEmpty())
                assertNull(AuditChains.verify(records), "chain ${chain ?: "platform"} verifies")
            }
        }
    }

    /** One of each action of §12 that this step can cause, staff ones in the restaurant's chain, the rest in the platform's. */
    private suspend fun everyAuthAction(
        client: HttpClient,
        clock: MutableClock,
        secret: ByteArray,
    ) {
        val refresh = """{"audience": "staff"}"""
        client.signIn("nobody@pho.example")
        repeat(10) { client.signIn("lan@pho.example", "wrong password") }
        clock.advance(16.minutes)
        val first = client.signIn("lan@pho.example").cookie("__Secure-rt-staff")!!
        repeat(2) { client.postJson("/v1/auth/refresh", refresh) { header(HttpHeaders.Cookie, "__Secure-rt-staff=$first") } }
        val token = client.signIn("lan@pho.example").field("access_token")
        val change = """{"current_password": "${AuthFixtures.PASSWORD}", "new_password": "a brand new passphrase"}"""
        client.postJson("/v1/auth/password", change) { bearerAuth(token) }
        val again = client.signIn("lan@pho.example", "a brand new passphrase").field("access_token")
        assertEquals(HttpStatusCode.NoContent, client.postJson("/v1/auth/sign-out", refresh) { bearerAuth(again) }.status)
        for (code in listOf("000000", Totp.code(secret, Totp.step(clock.now.epochSecond)))) {
            val mfaToken = client.signIn("ops@platform.example").field("mfa_token")
            client.postJson("/v1/auth/mfa", """{"mfa_token": "$mfaToken", "code": "$code"}""")
        }
    }

    @Test
    fun `IT-CORE-09 sign-ins leave no email, password, token or IP in the logs`() {
        root.level = Level.DEBUG
        try {
            authApp {
                AuthFixtures.staff(AuthFixtures.restaurant(), "lan@pho.example")
                val ok = client.signIn("lan@pho.example")
                client.signIn("lan@pho.example", "my secret guess")
                client.signIn("nobody@pho.example", "another secret guess")
                val token = ok.field("access_token")
                val refresh = ok.cookie("__Secure-rt-staff")!!
                client.postJson(
                    "/v1/auth/refresh",
                    """{"audience": "staff"}""",
                ) { header(HttpHeaders.Cookie, "__Secure-rt-staff=$refresh") }

                val logged =
                    appender.list.joinToString("\n") { e ->
                        e.formattedMessage + e.mdcPropertyMap + (e.throwableProxy?.let { "\n" + ThrowableProxyUtil.asString(it) } ?: "")
                    }
                assertTrue("/v1/auth/sign-in" in logged, "the calls are logged:\n$logged")
                for (secret in listOf(
                    "lan@pho.example",
                    "nobody@pho.example",
                    AuthFixtures.PASSWORD,
                    "my secret guess",
                    token,
                    refresh,
                    "203.0.113.7",
                )) {
                    assertTrue(secret !in logged, "found ${secret.take(12)}… in the logs")
                }
            }
        } finally {
            root.level = Level.INFO
        }
    }
}
