package io.github.phongmanh.restaurant.support

import io.github.phongmanh.restaurant.core.config.AppConfig
import io.github.phongmanh.restaurant.core.http.HttpSettings
import io.github.phongmanh.restaurant.core.http.SemVer
import io.github.phongmanh.restaurant.core.persistence.DatabaseSettings
import io.github.phongmanh.restaurant.core.persistence.JdbcTarget
import io.github.phongmanh.restaurant.core.persistence.Migrations
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import kotlin.time.Duration.Companion.seconds

/**
 * One PostgreSQL 18 for the whole test run (Testcontainers; Docker must be running), set up like
 * Neon: the migrator is a non-superuser that owns the database and may create roles, and the
 * migrations create `app` and `app_platform` (ADR-0004).
 */
object TestDatabase {
    private const val DB = "restaurant"

    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:18-alpine").also { it.start() }
    }

    private fun target(
        user: String,
        password: String,
    ) = JdbcTarget(container.jdbcUrl.substringBeforeLast('/') + "/$DB", user, password)

    val migrator: JdbcTarget by lazy {
        superuser(container.databaseName).use { conn ->
            conn.createStatement().use {
                // Like Neon's owner role: CREATEROLE, and BYPASSRLS so it may grant BYPASSRLS
                // (PostgreSQL 16+ requires it) to app_platform.
                it.execute("CREATE ROLE migrator LOGIN CREATEROLE BYPASSRLS PASSWORD 'migrator-test-password'")
                it.execute("CREATE DATABASE $DB OWNER migrator")
            }
        }
        target("migrator", "migrator-test-password")
    }

    val app: JdbcTarget by lazy { target("app", "app-test-password") }
    val platform: JdbcTarget by lazy { target("app_platform", "platform-test-password") }

    private val migrated: Unit by lazy { Migrations.run(migrator, app, platform).let { } }

    /** Settings for the migrated test database. */
    val settings: DatabaseSettings by lazy {
        migrated
        DatabaseSettings(
            app = app,
            platform = platform,
            maxPoolSize = 10,
            platformMaxPoolSize = 3,
            connectionTimeout = 2.seconds,
            statementTimeout = 5.seconds,
        )
    }

    val config: AppConfig by lazy {
        AppConfig(
            environment = "test",
            database = settings,
            http =
                HttpSettings(
                    allowedOrigins = listOf("https://order.example.test"),
                    minClientVersions = mapOf("customer-android" to SemVer(1, 0, 0)),
                ),
            auth = TestKeys.auth,
            rateLimits = TestKeys.rateLimits,
        )
    }

    /** A plain connection as [target] (autocommit off), for checks the application can't make. */
    fun connect(target: JdbcTarget): Connection =
        DriverManager.getConnection(target.jdbcUrl, target.user, target.password).also { it.autoCommit = false }

    /** The container's superuser, on [database]. For setup and for tampering with rows in tests. */
    fun superuser(database: String = DB): Connection {
        val url = container.jdbcUrl.substringBeforeLast('/') + "/$database"
        return DriverManager.getConnection(url, container.username, container.password)
    }

    /** Empties the core and auth tables between tests. */
    fun truncate() {
        migrated
        superuser().use { conn ->
            conn.createStatement().use {
                it.execute(
                    "TRUNCATE idempotency_keys, audit_events, audit_chain_heads, staff_link_tokens, staff_mfa_challenges, " +
                        "staff_refresh_tokens, staff_sessions, staff_accounts, restaurants",
                )
            }
        }
    }
}
