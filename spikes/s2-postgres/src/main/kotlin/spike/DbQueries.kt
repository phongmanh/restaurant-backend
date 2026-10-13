package spike

import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** The SQL behind each endpoint. Blocking; the caller runs it on the IO dispatcher. */
class DbQueries(private val db: Database) {

    /** Q1. `queryMs` times only the SELECT, not the commit after it. */
    fun ping(via: Via): PingResult {
        var queryMs = 0.0
        val timed = db.transaction(via) { conn ->
            val mark = kotlin.time.TimeSource.Monotonic.markNow()
            conn.createStatement().use { it.executeQuery("SELECT 1").use { rs -> rs.next() } }
            queryMs = mark.elapsedNow().toMillis()
        }
        return PingResult(via = via.name.lowercase(), acquireMs = timed.acquireMs, queryMs = queryMs, txMs = timed.txMs)
    }

    /**
     * Q2 and Q3: what placing an order writes, in one transaction. The idempotency key is claimed
     * first; if it was claimed before, the transaction stops there and returns the earlier order.
     * Seven round trips: set tenant, claim key, order, lines (one batch), event, outbox, commit.
     */
    fun placeOrder(via: Via, restaurantId: UUID, key: String, prepareThreshold: Int): OrderResult {
        val orderId = UUID.randomUUID()
        var replayed = false
        val timed = db.transaction(via, restaurantId, prepareThreshold) { conn ->
            val claimed = conn.prepareStatement(
                "INSERT INTO idempotency_keys (restaurant_id, key, order_id) VALUES (?, ?, ?) " +
                    "ON CONFLICT DO NOTHING RETURNING order_id",
            ).use {
                it.setObject(1, restaurantId)
                it.setString(2, key)
                it.setObject(3, orderId)
                it.executeQuery().use { rs -> rs.next() }
            }
            if (!claimed) {
                replayed = true
                return@transaction existingOrder(conn, restaurantId, key)
            }
            conn.prepareStatement("INSERT INTO orders (id, restaurant_id, status, total_minor) VALUES (?, ?, 'PLACED', ?)").use {
                it.setObject(1, orderId)
                it.setObject(2, restaurantId)
                it.setLong(3, ORDER_LINES.sumOf { line -> line.second * line.third })
                it.executeUpdate()
            }
            // One batch: pgjdbc sends the three inserts in one round trip.
            conn.prepareStatement(
                "INSERT INTO order_lines (id, restaurant_id, order_id, item, quantity, price_minor) VALUES (?, ?, ?, ?, ?, ?)",
            ).use {
                for ((item, quantity, price) in ORDER_LINES) {
                    it.setObject(1, UUID.randomUUID())
                    it.setObject(2, restaurantId)
                    it.setObject(3, orderId)
                    it.setString(4, item)
                    it.setInt(5, quantity)
                    it.setLong(6, price)
                    it.addBatch()
                }
                it.executeBatch()
            }
            conn.prepareStatement(
                "INSERT INTO events (id, restaurant_id, order_id, type, payload) VALUES (?, ?, ?, 'ORDER_PLACED', ?::jsonb)",
            ).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, restaurantId)
                it.setObject(3, orderId)
                it.setString(4, """{"orderId":"$orderId"}""")
                it.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO outbox (id, restaurant_id, topic, payload) VALUES (?, ?, 'order.placed', ?::jsonb)",
            ).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, restaurantId)
                it.setString(3, """{"orderId":"$orderId"}""")
                it.executeUpdate()
            }
            orderId
        }
        // Q3 evidence, outside the timed transaction: how many prepared statements the server
        // backend holds. Over a pooler this is the backend the next transaction got, so a count > 0
        // shows named statements reach the server, and 0 errors shows the pooler keeps them working.
        val serverPrepared = if (prepareThreshold == 0) null else db.transaction(via, prepareThreshold = 0) { conn ->
            count(conn, "SELECT count(*) FROM pg_prepared_statements").toInt()
        }.value
        return OrderResult(
            via = via.name.lowercase(),
            prepareThreshold = prepareThreshold,
            orderId = timed.value.toString(),
            replayed = replayed,
            acquireMs = timed.acquireMs,
            txMs = timed.txMs,
            serverPreparedStatements = serverPrepared,
        )
    }

    private fun existingOrder(conn: Connection, restaurantId: UUID, key: String): UUID =
        conn.prepareStatement("SELECT order_id FROM idempotency_keys WHERE restaurant_id = ? AND key = ?").use {
            it.setObject(1, restaurantId)
            it.setString(2, key)
            it.executeQuery().use { rs -> rs.next(); rs.getObject(1, UUID::class.java) }
        }

    /** Q4: fixed checks. Each runs in its own transaction; the writes are rolled back. */
    fun rlsChecks(via: Via): RlsResult {
        val checks = mutableListOf<RlsCheck>()
        fun check(name: String, tenant: UUID?, body: (Connection) -> Pair<Boolean, String>) {
            val (pass, detail) = try {
                db.transaction(via, tenant) { conn -> body(conn).also { conn.rollback() } }.value
            } catch (e: SQLException) {
                false to "error ${e.sqlState}: ${e.message}"
            }
            checks += RlsCheck(name, pass, detail)
        }

        check("app role can't bypass RLS", null) { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT current_user, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user").use { rs ->
                    rs.next()
                    val pass = !rs.getBoolean(2) && !rs.getBoolean(3)
                    pass to "current_user=${rs.getString(1)} rolsuper=${rs.getBoolean(2)} rolbypassrls=${rs.getBoolean(3)}"
                }
            }
        }
        check("no tenant set: 0 rows, no error", null) { conn ->
            val n = count(conn, "SELECT count(*) FROM orders")
            (n == 0L) to "orders visible: $n"
        }
        check("no tenant set: insert refused", null) { conn ->
            refused(conn, "INSERT INTO orders (id, restaurant_id, status, total_minor) VALUES (gen_random_uuid(), '$RESTAURANT_A', 'X', 0)")
        }
        check("tenant A sees only A's orders", RESTAURANT_A) { conn ->
            val own = count(conn, "SELECT count(*) FROM orders WHERE restaurant_id = '$RESTAURANT_A'")
            val other = count(conn, "SELECT count(*) FROM orders WHERE restaurant_id <> '$RESTAURANT_A'")
            (own > 0 && other == 0L) to "own=$own other=$other"
        }
        check("tenant A can't read B's order by id", RESTAURANT_A) { conn ->
            val n = count(conn, "SELECT count(*) FROM orders WHERE id = '00000000-0000-0000-0001-00000000000b'")
            (n == 0L) to "rows: $n"
        }
        check("tenant A sees only its restaurant", RESTAURANT_A) { conn ->
            val n = count(conn, "SELECT count(*) FROM restaurants")
            (n == 1L) to "restaurants visible: $n"
        }
        check("tenant A can't insert a row for B", RESTAURANT_A) { conn ->
            refused(conn, "INSERT INTO orders (id, restaurant_id, status, total_minor) VALUES (gen_random_uuid(), '$RESTAURANT_B', 'X', 0)")
        }
        check("tenant A can't update B's rows", RESTAURANT_A) { conn ->
            val n = conn.createStatement().use { it.executeUpdate("UPDATE orders SET status = 'HIJACKED' WHERE restaurant_id = '$RESTAURANT_B'") }
            (n == 0) to "rows updated: $n"
        }
        check("tenant A can't move its row to B", RESTAURANT_A) { conn ->
            refused(conn, "UPDATE orders SET restaurant_id = '$RESTAURANT_B' WHERE id = '00000000-0000-0000-0001-00000000000a'")
        }
        check("tenant A can't delete B's rows", RESTAURANT_A) { conn ->
            val n = conn.createStatement().use { it.executeUpdate("DELETE FROM outbox WHERE restaurant_id = '$RESTAURANT_B'") }
            (n == 0) to "rows deleted: $n"
        }
        return RlsResult(via.name.lowercase(), checks.all { it.pass }, checks)
    }

    /**
     * Q4 burst: one transaction as [restaurantId], then one with no tenant set. Under the pooler the
     * second may run on another server connection, possibly one that just served another tenant;
     * `SET LOCAL` must not have survived on any of them.
     */
    fun tenantProbe(via: Via, restaurantId: UUID): TenantProbe {
        val first = db.transaction(via, restaurantId) { conn -> sessionView(conn) }.value
        val after = db.transaction(via) { conn -> sessionView(conn) }.value
        return TenantProbe(
            requested = restaurantId.toString(),
            setting = first.setting,
            visibleRestaurants = first.visible,
            backendPid = first.pid,
            settingAfterCommit = after.setting,
            visibleAfterCommit = after.visible,
            backendPidAfterCommit = after.pid,
        )
    }

    private class SessionView(val setting: String?, val visible: List<String>, val pid: Int)

    private fun sessionView(conn: Connection): SessionView = conn.createStatement().use { st ->
        st.executeQuery(
            "SELECT current_setting('app.restaurant_id', true), pg_backend_pid(), " +
                "(SELECT coalesce(array_agg(DISTINCT restaurant_id::text), '{}') FROM orders)",
        ).use { rs ->
            rs.next()
            @Suppress("UNCHECKED_CAST")
            val visible = (rs.getArray(3).array as Array<String>).toList()
            SessionView(rs.getString(1), visible, rs.getInt(2))
        }
    }

    /**
     * Q5: try the lock and, if it's ours, hold it for [holdMs] inside the transaction. Times are the
     * database's clock, so intervals from different instances compare. After commit, one more
     * transaction tries the key again: true shows commit released it.
     */
    fun advisoryLock(via: Via, key: Long, holdMs: Long): LockResult {
        val held = db.transaction(via) { conn ->
            val acquired = conn.prepareStatement("SELECT pg_try_advisory_xact_lock(?)").use {
                it.setLong(1, key)
                it.executeQuery().use { rs -> rs.next(); rs.getBoolean(1) }
            }
            val from = dbNowMs(conn)
            if (acquired) {
                conn.prepareStatement("SELECT pg_sleep(?)").use {
                    it.setDouble(1, holdMs / 1000.0)
                    it.executeQuery().close()
                }
            }
            Triple(acquired, from, dbNowMs(conn))
        }.value
        val reacquired = db.transaction(via) { conn ->
            conn.prepareStatement("SELECT pg_try_advisory_xact_lock(?)").use {
                it.setLong(1, key)
                it.executeQuery().use { rs -> rs.next(); rs.getBoolean(1) }
            }
        }.value
        return LockResult(
            via = via.name.lowercase(),
            acquired = held.first,
            heldFromMs = held.second,
            heldUntilMs = held.third,
            reacquiredAfterCommit = reacquired,
        )
    }

    private fun dbNowMs(conn: Connection): Double = conn.createStatement().use {
        it.executeQuery("SELECT extract(epoch FROM clock_timestamp()) * 1000").use { rs -> rs.next(); rs.getDouble(1) }
    }

    /** Q7: server connections by role and state, over the direct connection. */
    fun connections(): ConnectionsResult = db.transaction(Via.DIRECT) { conn ->
        conn.createStatement().use { st ->
            val maxConnections = st.executeQuery("SHOW max_connections").use { rs -> rs.next(); rs.getInt(1) }
            val rows = st.executeQuery(
                "SELECT coalesce(usename, '(none)'), coalesce(state, '(hidden)'), count(*) " +
                    "FROM pg_stat_activity WHERE backend_type = 'client backend' GROUP BY 1, 2 ORDER BY 1, 2",
            ).use { rs ->
                buildList { while (rs.next()) add(ConnectionCount(rs.getString(1), rs.getString(2), rs.getInt(3))) }
            }
            ConnectionsResult(maxConnections = maxConnections, byRoleAndState = rows)
        }
    }.value

    private fun count(conn: Connection, sql: String): Long =
        conn.createStatement().use { it.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }

    /** Passes when the statement fails with 42501 (RLS WITH CHECK violation). */
    private fun refused(conn: Connection, sql: String): Pair<Boolean, String> = try {
        conn.createStatement().use { it.executeUpdate(sql) }
        false to "allowed"
    } catch (e: SQLException) {
        (e.sqlState == "42501") to "refused ${e.sqlState}"
    }

    private companion object {
        /** item, quantity, price in minor units. */
        val ORDER_LINES = listOf(Triple("Pho bo", 2, 6_500L), Triple("Spring rolls", 1, 4_000L), Triple("Iced tea", 2, 1_500L))
    }
}

@Serializable
data class PingResult(val instanceId: String = "", val via: String, val acquireMs: Double, val queryMs: Double, val txMs: Double)

@Serializable
data class OrderResult(
    val instanceId: String = "",
    val via: String,
    val prepareThreshold: Int,
    val orderId: String,
    val replayed: Boolean,
    val acquireMs: Double,
    val txMs: Double,
    val serverPreparedStatements: Int? = null,
)

@Serializable
data class RlsCheck(val name: String, val pass: Boolean, val detail: String)

@Serializable
data class RlsResult(val via: String, val allPass: Boolean, val checks: List<RlsCheck>)

@Serializable
data class TenantProbe(
    val instanceId: String = "",
    val requested: String,
    val setting: String?,
    val visibleRestaurants: List<String>,
    val backendPid: Int,
    val settingAfterCommit: String?,
    val visibleAfterCommit: List<String>,
    val backendPidAfterCommit: Int,
)

@Serializable
data class LockResult(
    val instanceId: String = "",
    val via: String,
    val acquired: Boolean,
    val heldFromMs: Double,
    val heldUntilMs: Double,
    val reacquiredAfterCommit: Boolean,
)

@Serializable
data class ConnectionCount(val role: String, val state: String, val count: Int)

@Serializable
data class ConnectionsResult(
    val instanceId: String = "",
    val maxConnections: Int,
    val byRoleAndState: List<ConnectionCount>,
    val pools: Map<String, PoolStats> = emptyMap(),
)
