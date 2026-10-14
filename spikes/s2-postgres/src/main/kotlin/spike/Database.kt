package spike

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.Serializable
import org.postgresql.PGConnection
import java.sql.Connection
import java.util.UUID
import kotlin.time.TimeSource

/** Which way a request reaches PostgreSQL: Neon's transaction-mode pooler, or the database itself. */
enum class Via { POOLED, DIRECT }

/**
 * The app's two connection pools, both as the `app` role. ADR-0004's settings: at most 10
 * connections per instance (3 in runs 1 and 2, where requests queued), a 2 s wait for a connection
 * so a request fails fast instead of queueing, short idle timeouts, and pgjdbc `prepareThreshold=0`
 * unless a request asks for another value (Q3).
 */
class Database(pooled: JdbcTarget, direct: JdbcTarget) : AutoCloseable {
    private val pools: Map<Via, TimedPool> = mapOf(
        Via.POOLED to TimedPool.open("pooled", pooled),
        Via.DIRECT to TimedPool.open("direct", direct),
    )

    fun pool(via: Via): HikariDataSource = pools.getValue(via).dataSource

    fun poolStats(): Map<String, PoolStats> = pools.entries.associate { (via, pool) ->
        val mx = pool.dataSource.hikariPoolMXBean
        via.name.lowercase() to PoolStats(pool.startMs, mx.activeConnections, mx.idleConnections, mx.totalConnections, mx.threadsAwaitingConnection)
    }

    /**
     * Runs [block] in one transaction and times it. With [restaurantId] the transaction starts with
     * `set_config('app.restaurant_id', id, true)`, which is `SET LOCAL` in a form that takes a bind
     * parameter. The block's exceptions roll the transaction back and propagate.
     */
    fun <T> transaction(
        via: Via,
        restaurantId: UUID? = null,
        prepareThreshold: Int = DEFAULT_PREPARE_THRESHOLD,
        block: (Connection) -> T,
    ): Timed<T> {
        val acquire = TimeSource.Monotonic.markNow()
        pool(via).connection.use { conn ->
            val acquireMs = acquire.elapsedNow().toMillis()
            // Per checkout, because Hikari hands the same physical connection to the next request.
            conn.unwrap(PGConnection::class.java).prepareThreshold = prepareThreshold
            val tx = TimeSource.Monotonic.markNow()
            try {
                if (restaurantId != null) setTenant(conn, restaurantId)
                val value = block(conn)
                conn.commit()
                return Timed(value, acquireMs, tx.elapsedNow().toMillis())
            } catch (e: Throwable) {
                runCatching { conn.rollback() }.onFailure(e::addSuppressed)
                throw e
            }
        }
    }

    override fun close() = pools.values.forEach { it.dataSource.close() }

    private class TimedPool(val dataSource: HikariDataSource, val startMs: Double) {
        companion object {
            fun open(name: String, target: JdbcTarget): TimedPool {
                val config = HikariConfig().apply {
                    poolName = name
                    jdbcUrl = target.jdbcUrl
                    username = target.user
                    password = target.password
                    maximumPoolSize = MAX_POOL_SIZE
                    minimumIdle = 1
                    idleTimeout = IDLE_TIMEOUT_MS
                    maxLifetime = MAX_LIFETIME_MS
                    connectionTimeout = CONNECTION_TIMEOUT_MS
                    isAutoCommit = false
                    addDataSourceProperty("prepareThreshold", DEFAULT_PREPARE_THRESHOLD)
                    addDataSourceProperty("ApplicationName", "s2-spike")
                }
                // Hikari opens the first connection here and fails fast, so this is the connect time
                // (TCP + TLS + auth, and a Neon compute wake-up if it was suspended) that a cold start pays.
                val mark = TimeSource.Monotonic.markNow()
                val dataSource = HikariDataSource(config)
                return TimedPool(dataSource, mark.elapsedNow().toMillis())
            }
        }
    }

    companion object {
        const val DEFAULT_PREPARE_THRESHOLD = 0
        private const val MAX_POOL_SIZE = 10
        private const val IDLE_TIMEOUT_MS = 30_000L
        private const val MAX_LIFETIME_MS = 300_000L
        private const val CONNECTION_TIMEOUT_MS = 2_000L

        fun setTenant(conn: Connection, restaurantId: UUID) {
            conn.prepareStatement("SELECT set_config('app.restaurant_id', ?, true)").use {
                it.setString(1, restaurantId.toString())
                it.executeQuery().close()
            }
        }
    }
}

/** A result with how long getting a connection took and how long the transaction itself took. */
data class Timed<T>(val value: T, val acquireMs: Double, val txMs: Double)

@Serializable
data class PoolStats(val startMs: Double, val active: Int, val idle: Int, val total: Int, val waiting: Int)

/** Milliseconds with two decimals: database round trips in one AWS region are about 1 ms. */
fun kotlin.time.Duration.toMillis(): Double = inWholeMicroseconds / 10 / 100.0
