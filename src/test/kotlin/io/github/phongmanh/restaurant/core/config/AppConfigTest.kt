package io.github.phongmanh.restaurant.core.config

import io.github.phongmanh.restaurant.core.application.RateLimitPolicy
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
    private val secretUrl = "postgresql://app:s3cret-value@db.example.test/restaurant?sslmode=require&channel_binding=require"

    private fun config(vararg overrides: Pair<String, String>) =
        MapApplicationConfig(
            "app.environment" to "dev",
            "database.url" to secretUrl,
            "database.platformUrl" to "postgresql://app_platform:other-secret@db.example.test/restaurant",
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
            "auth.argon2.memoryKib" to "19456",
            "auth.argon2.iterations" to "2",
            "auth.argon2.parallelism" to "1",
            "auth.argon2.maxConcurrent" to "4",
            "auth.argon2.slotWaitMs" to "2000",
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
        assertEquals("jdbc:postgresql://db.example.test/restaurant?sslmode=require&channelBinding=require", c.database.app.jdbcUrl)
        assertEquals(5.seconds, c.database.statementTimeout)
        assertEquals(listOf("https://order.example.test", "http://localhost:5173"), c.http.allowedOrigins)
        assertEquals(mapOf("customer-android" to SemVer(1, 2, 3)), c.http.minClientVersions)
    }

    @Test
    fun `a missing secret stops startup and is named, without any value`() {
        val e = assertFailsWith<IllegalStateException> { AppConfig.load(config("database.url" to "", "database.platformUrl" to "")) }
        assertTrue("DATABASE_URL" in e.message!! && "DATABASE_URL_PLATFORM" in e.message!!, e.message)
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
    }

    @Test
    fun `missing auth secrets and, outside dev, Upstash stop startup`() {
        val e =
            assertFailsWith<IllegalStateException> {
                AppConfig.load(config("app.environment" to "staging", "auth.staffJwtKeys" to "", "auth.emailHmacKey" to ""))
            }
        listOf("STAFF_JWT_KEYS", "EMAIL_HMAC_KEY", "UPSTASH_REDIS_REST_URL", "UPSTASH_REDIS_REST_TOKEN").forEach {
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
    fun `production refuses a localhost origin`() {
        val upstash = arrayOf("rateLimits.upstashUrl" to "https://x.upstash.io", "rateLimits.upstashToken" to "t")
        val e = assertFailsWith<IllegalStateException> { AppConfig.load(config("app.environment" to "production", *upstash)) }
        assertTrue("localhost" in e.message!!, e.message)
        AppConfig.load(config("app.environment" to "production", "http.allowedOrigins" to "https://order.example.test", *upstash))
    }

    @Test
    fun `a database target never prints its password`() {
        val c = AppConfig.load(config())
        assertFalse("s3cret-value" in c.toString(), c.toString())
    }
}
