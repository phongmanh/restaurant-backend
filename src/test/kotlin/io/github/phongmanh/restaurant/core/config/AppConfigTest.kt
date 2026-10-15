package io.github.phongmanh.restaurant.core.config

import io.github.phongmanh.restaurant.core.http.SemVer
import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun `production refuses a localhost origin`() {
        assertFailsWith<IllegalStateException> { AppConfig.load(config("app.environment" to "production")) }
        AppConfig.load(config("app.environment" to "production", "http.allowedOrigins" to "https://order.example.test"))
    }

    @Test
    fun `a database target never prints its password`() {
        val c = AppConfig.load(config())
        assertFalse("s3cret-value" in c.toString(), c.toString())
    }
}
