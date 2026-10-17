package io.github.phongmanh.restaurant.core.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.time.Duration

/** Database settings from `AppConfig` (architecture §3, ADR-0004). */
data class DatabaseSettings(
    /** The `app` role, through the pooler. Row-level security applies to it. */
    val app: JdbcTarget,
    /** The `app_platform` role, through the pooler. It bypasses row-level security (§7). */
    val platform: JdbcTarget,
    val maxPoolSize: Int,
    val platformMaxPoolSize: Int,
    /** How long a request waits for a free connection before it gets a 503. */
    val connectionTimeout: Duration,
    /** The longest a statement may run, set in every transaction (§7). */
    val statementTimeout: Duration,
)

/**
 * The connection pools, one per database role, opened at startup so a cold start pays the connect
 * time once (S-1 Q10 design rule). Close it when the application stops.
 */
class DatabasePools(
    val settings: DatabaseSettings,
    /** Pool usage and waits (architecture §13); `null` for the tools that open pools outside the service. */
    private val metrics: MeterRegistry? = null,
) : AutoCloseable {
    private val appPool = pool("app", settings.app, settings.maxPoolSize, minIdle = 1)

    // Used rarely (admin work, jobs, sign-in), so it keeps no idle connection.
    private val platformPool = pool("platform", settings.platform, settings.platformMaxPoolSize, minIdle = 0)

    init {
        metrics?.let { registry -> listOf(appPool, platformPool).forEach { gauges(it, registry) } }
    }

    val app: Database = Database.connect(appPool, databaseConfig = exposedConfig)
    val platform: Database = Database.connect(platformPool, databaseConfig = exposedConfig)

    override fun close() {
        appPool.close()
        platformPool.close()
    }

    /**
     * Pool usage and waits from the pool's own counters (architecture §13): connections in use, idle,
     * at most, and requests waiting for one. HikariCP's Micrometer tracker isn't used: with it the
     * test suite, which starts hundreds of applications, ran out of heap.
     */
    private fun gauges(
        pool: HikariDataSource,
        registry: MeterRegistry,
    ) {
        fun gauge(
            name: String,
            value: (HikariDataSource) -> Int,
        ) = Gauge
            .builder(name, pool) { p -> p.hikariPoolMXBean?.let { value(p).toDouble() } ?: 0.0 }
            .tag("pool", pool.poolName)
            .register(registry)
        gauge("db.pool.connections.active") { it.hikariPoolMXBean.activeConnections }
        gauge("db.pool.connections.idle") { it.hikariPoolMXBean.idleConnections }
        gauge("db.pool.connections.pending") { it.hikariPoolMXBean.threadsAwaitingConnection }
        gauge("db.pool.connections.max") { it.maximumPoolSize }
    }

    private fun pool(
        name: String,
        target: JdbcTarget,
        maxSize: Int,
        minIdle: Int,
    ) = HikariDataSource(
        HikariConfig().apply {
            poolName = name
            jdbcUrl = target.jdbcUrl
            username = target.user
            password = target.password
            maximumPoolSize = maxSize
            minimumIdle = minIdle
            idleTimeout = IDLE_TIMEOUT_MS
            maxLifetime = MAX_LIFETIME_MS
            connectionTimeout = settings.connectionTimeout.inWholeMilliseconds
            // Start without waiting for the first connection and fill the pool in the background.
            // If the database is down at a cold start, the instance still starts and answers
            // 503 service_unavailable (architecture §10) instead of crashing.
            initializationFailTimeout = -1
            isAutoCommit = false
            // Driver properties must be strings: Hikari hands them to pgjdbc as java.util.Properties,
            // whose getProperty() returns null for anything else, so 0 or false would be ignored.
            //
            // Neon's pooler runs in transaction mode, which can't keep server-side prepared
            // statements between transactions (S-2 Q3).
            addDataSourceProperty("prepareThreshold", "0")
            // pgjdbc otherwise copies the server's "Detail:" line into exception messages, and that
            // line quotes row values (e.g. "Key (email)=(…) already exists"). Values stay out of logs.
            addDataSourceProperty("logServerErrorDetail", "false")
            addDataSourceProperty("ApplicationName", "restaurant-backend")
        },
    )

    private companion object {
        const val IDLE_TIMEOUT_MS = 30_000L
        const val MAX_LIFETIME_MS = 300_000L

        val exposedConfig =
            DatabaseConfig {
                // Exposed retries a failed transaction 3 times by default, which would run an
                // operation's work again. A request is retried by its client, with its
                // Idempotency-Key, or not at all.
                defaultMaxAttempts = 1
            }
    }
}
