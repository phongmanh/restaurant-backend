package io.github.phongmanh.restaurant.core.config

import io.github.phongmanh.restaurant.core.application.RateLimitPolicy
import io.github.phongmanh.restaurant.core.auth.persistence.LinkKind
import io.github.phongmanh.restaurant.core.http.SemVer
import io.github.phongmanh.restaurant.support.TestKeys
import io.ktor.server.config.MapApplicationConfig
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The startup checks of core-and-auth §13. */
class AppConfigTest {
    private val secretUrl = "postgresql://app:s3cret-value@db.example.test/restaurant?sslmode=verify-full&channel_binding=require"

    private fun config(vararg overrides: Pair<String, String>) =
        MapApplicationConfig(
            "app.environment" to "dev",
            "database.url" to secretUrl,
            "database.platformUrl" to "postgresql://app_platform:other-secret@db.example.test/restaurant?sslmode=verify-full",
            "database.maxPoolSize" to "10",
            "database.platformMaxPoolSize" to "3",
            "database.connectionTimeoutMs" to "2000",
            "database.statementTimeoutMs" to "5000",
            "http.allowedOrigins" to "https://order.example.test, http://localhost:5173",
            "http.minClientVersions.customer-android" to "1.2.3",
            "auth.issuer" to "https://api.example.test",
            "auth.staffJwtKeys" to TestKeys.staffKeysJson,
            "auth.totpEncryptionKeys" to TestKeys.totpKeysJson,
            "auth.emailHmacKey" to Base64.getEncoder().encodeToString(ByteArray(32)),
            "auth.cookiePath" to "/api/v1/auth",
            "auth.staffAppUrl" to "http://localhost:5173",
            "auth.adminAppUrl" to "http://localhost:5174",
            "auth.argon2.memoryKib" to "19456",
            "auth.argon2.iterations" to "2",
            "auth.argon2.parallelism" to "1",
            "auth.argon2.maxConcurrent" to "4",
            "auth.argon2.slotWaitMs" to "2000",
            "guests.sessionJwtKeys" to TestKeys.guestKeysJson,
            "guests.qrSigningKeys" to TestKeys.qrKeysJson,
            "guests.customerAppUrl" to TestKeys.CUSTOMER_APP,
            "jobs.cronSecret" to TestKeys.CRON_SECRET,
            "realtime.livePush" to "ably",
            "realtime.ablyApiKey" to TestKeys.ABLY_KEY,
            "observability.otlpEndpoint" to "https://otlp.example.test/otlp",
            "observability.otlpHeaders" to "Authorization=Basic b3RscC1zZWNyZXQ=",
            "rateLimits.upstashUrl" to "",
            "rateLimits.upstashToken" to "",
            "rateLimits.timeoutMs" to "200",
            "rateLimits.policies.auth.sign_in" to "30/60",
            "rateLimits.policies.auth.password_change" to "5/900",
            *overrides,
        )

    @Test
    fun `reads every setting`() {
        val c = AppConfig.load(config())
        assertEquals("app", c.database.app.user)
        assertEquals(
            "jdbc:postgresql://db.example.test/restaurant?sslmode=verify-full&channelBinding=require" +
                "&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory",
            c.database.app.jdbcUrl,
        )
        assertEquals(5.seconds, c.database.statementTimeout)
        assertEquals(listOf("https://order.example.test", "http://localhost:5173"), c.http.allowedOrigins)
        assertEquals(mapOf("customer-android" to SemVer(1, 2, 3)), c.http.minClientVersions)
        assertEquals(TestKeys.ABLY_KEY, c.realtime.ablyApiKey)
        assertNull(AppConfig.load(config("realtime.livePush" to "", "realtime.ablyApiKey" to "")).realtime.ablyApiKey, "optional in dev")
    }

    @Test
    fun `a missing secret stops startup and is named, without any value`() {
        val e = assertFailsWith<IllegalStateException> { AppConfig.load(config("database.url" to "", "database.platformUrl" to "")) }
        assertTrue("DATABASE_URL" in e.message!! && "DATABASE_URL_PLATFORM" in e.message!!, e.message)
    }

    @Test
    fun `a database URL that doesn't verify the server stops startup, without its password`() {
        val weak = secretUrl.replace("verify-full", "require")
        val e = assertFailsWith<IllegalArgumentException> { AppConfig.load(config("database.url" to weak)) }
        assertTrue("verify-full" in e.message!! && "s3cret-value" !in e.message!!, e.message)
    }

    @Test
    fun `reads the auth keys and the rate limits`() {
        val c = AppConfig.load(config())
        assertEquals("staff-2", c.auth.staffKeys.signing.kid)
        assertEquals(19_456, c.auth.argon2.memoryKib)
        assertEquals(2.seconds, c.auth.argon2.slotWait)
        assertEquals(RateLimitPolicy("auth.sign_in", 30, 60.seconds), c.rateLimits.policy("auth.sign_in"))
        assertEquals(RateLimitPolicy("auth.password_change", 5, 900.seconds), c.rateLimits.policy("auth.password_change"))
        assertNull(c.rateLimits.upstash, "in-memory limits in dev")
        assertEquals("guest-1", c.guests.sessionKeys.signing.kid)
        assertEquals(
            listOf("2", "1"),
            c.guests.qrKeys.keys
                .map { it.kid },
        )
        assertEquals(TestKeys.CRON_SECRET, c.jobs.cronSecret)
        assertFalse(TestKeys.CRON_SECRET in c.toString(), "no cron secret in toString")
    }

    @Test
    fun `missing guest keys and cron secret stop startup`() {
        val e =
            assertFailsWith<IllegalStateException> {
                AppConfig.load(config("guests.sessionJwtKeys" to "", "guests.qrSigningKeys" to "", "jobs.cronSecret" to ""))
            }
        listOf("GUEST_JWT_KEYS", "QR_SIGNING_KEYS", "CRON_SECRET").forEach { assertTrue(it in e.message!!, e.message) }
    }

    @Test
    fun `a short cron secret stops startup, without its value in the message`() {
        val short = "guessable-cron-secret"
        val e = assertFailsWith<IllegalStateException> { AppConfig.load(config("jobs.cronSecret" to short)) }
        assertTrue("CRON_SECRET" in e.message!!, e.message)
        assertFalse(short in e.message!!, "the message never holds the secret")
    }

    @Test
    fun `missing auth secrets and, outside dev, Upstash stop startup`() {
        val e =
            assertFailsWith<IllegalStateException> {
                AppConfig.load(
                    config(
                        "app.environment" to "staging",
                        "auth.staffJwtKeys" to "",
                        "auth.emailHmacKey" to "",
                        "realtime.ablyApiKey" to "",
                        "observability.otlpEndpoint" to "",
                        "observability.otlpHeaders" to "",
                    ),
                )
            }
        listOf(
            "STAFF_JWT_KEYS",
            "EMAIL_HMAC_KEY",
            "UPSTASH_REDIS_REST_URL",
            "UPSTASH_REDIS_REST_TOKEN",
            "ABLY_API_KEY",
            "OTEL_EXPORTER_OTLP_ENDPOINT",
            "OTEL_EXPORTER_OTLP_HEADERS",
        ).forEach {
            assertTrue(it in e.message!!, e.message)
        }
        val staging =
            AppConfig.load(
                config(
                    "app.environment" to "staging",
                    "rateLimits.upstashUrl" to "https://x.upstash.io",
                    "rateLimits.upstashToken" to "upstash-secret",
                ),
            )
        assertFalse("upstash-secret" in staging.toString(), "no secret in toString")
        assertFailsWith<IllegalStateException> { AppConfig.load(config("auth.emailHmacKey" to "c2hvcnQ=")) }
    }

    @Test
    fun `LIVE_PUSH is required outside dev, ably needs its key, off ignores it (CR-013)`() {
        val staging =
            arrayOf("app.environment" to "staging", "rateLimits.upstashUrl" to "https://x.upstash.io", "rateLimits.upstashToken" to "t")
        val unset = assertFailsWith<IllegalStateException> { AppConfig.load(config(*staging, "realtime.livePush" to "")) }
        assertTrue("LIVE_PUSH" in unset.message!!, unset.message)
        val noKey = assertFailsWith<IllegalStateException> { AppConfig.load(config(*staging, "realtime.ablyApiKey" to "")) }
        assertTrue("ABLY_API_KEY" in noKey.message!!, noKey.message)
        assertFailsWith<IllegalStateException> { AppConfig.load(config(*staging, "realtime.livePush" to "pusher")) }

        val off = AppConfig.load(config(*staging, "realtime.livePush" to "off")).realtime
        assertEquals(LivePush.OFF, off.livePush)
        assertNull(off.ablyApiKey, "with push off the key is never used")
        assertEquals(LivePush.ABLY, AppConfig.load(config(*staging)).realtime.livePush)
        assertFalse(TestKeys.ABLY_KEY in AppConfig.load(config(*staging)).realtime.toString(), "no key in toString")

        assertEquals(LivePush.ABLY, AppConfig.load(config("realtime.livePush" to "")).realtime.livePush, "dev: on with a key")
        val devWithoutKey = AppConfig.load(config("realtime.livePush" to "", "realtime.ablyApiKey" to ""))
        assertEquals(LivePush.OFF, devWithoutKey.realtime.livePush, "dev: off without")
    }

    @Test
    fun `production refuses a localhost origin`() {
        val upstash = arrayOf("rateLimits.upstashUrl" to "https://x.upstash.io", "rateLimits.upstashToken" to "t")
        val e = assertFailsWith<IllegalStateException> { AppConfig.load(config("app.environment" to "production", *upstash)) }
        assertTrue("localhost" in e.message!!, e.message)
        val apps = arrayOf("auth.staffAppUrl" to "https://staff.example.test", "auth.adminAppUrl" to "https://admin.example.test")
        AppConfig.load(config("app.environment" to "production", "http.allowedOrigins" to "https://order.example.test", *upstash, *apps))
    }

    @Test
    fun `production needs https app URLs for links`() {
        val production =
            arrayOf(
                "app.environment" to "production",
                "http.allowedOrigins" to "https://order.example.test",
                "rateLimits.upstashUrl" to "https://x.upstash.io",
                "rateLimits.upstashToken" to "t",
            )
        val e = assertFailsWith<IllegalStateException> { AppConfig.load(config(*production)) }
        assertTrue("auth.staffAppUrl" in e.message!!, e.message)
        val c =
            AppConfig.load(
                config(
                    *production,
                    "auth.staffAppUrl" to "https://staff.example.test/",
                    "auth.adminAppUrl" to "https://admin.example.test",
                ),
            )
        assertEquals("https://staff.example.test/invite#t0k", c.auth.links.of(LinkKind.INVITATION, platformAdmin = false, token = "t0k"))
        assertEquals(
            "https://admin.example.test/reset-password#t0k",
            c.auth.links.of(LinkKind.PASSWORD_RESET, platformAdmin = true, token = "t0k"),
        )
        val apps = arrayOf("auth.staffAppUrl" to "https://staff.example.test", "auth.adminAppUrl" to "https://admin.example.test")
        val local =
            assertFailsWith<IllegalStateException> {
                AppConfig.load(
                    config(
                        *production,
                        *apps,
                        "guests.customerAppUrl" to "http://localhost:5175",
                    ),
                )
            }
        assertTrue("guests.customerAppUrl" in local.message!!, local.message)
    }

    @Test
    fun `reads the metrics backend, and never prints its token (ADR-0009)`() {
        val c = AppConfig.load(config())
        assertEquals("https://otlp.example.test/otlp", c.observability.otlpEndpoint)
        assertEquals(mapOf("Authorization" to "Basic b3RscC1zZWNyZXQ="), c.observability.headers)
        assertEquals(10, c.observability.step.seconds)
        assertFalse("b3RscC1zZWNyZXQ=" in c.toString(), c.toString())
        val dev = AppConfig.load(config("observability.otlpEndpoint" to "", "observability.otlpHeaders" to ""))
        assertNull(dev.observability.otlpEndpoint, "dev may leave the backend out")
        assertFailsWith<IllegalArgumentException> { AppConfig.load(config("observability.otlpHeaders" to "no-equals-sign")) }
    }

    @Test
    fun `a database target never prints its password`() {
        val c = AppConfig.load(config())
        assertFalse("s3cret-value" in c.toString(), c.toString())
    }
}
