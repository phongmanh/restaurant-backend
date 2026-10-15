package io.github.phongmanh.restaurant.core.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.support.TestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.postgresql.PGConnection
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** IT-CORE-06 (core-and-auth §15): tenant transactions, row-level security and the two roles. */
class TransactorTest {
    private val a = RestaurantId(UuidV7.next())
    private val b = RestaurantId(UuidV7.next())
    private lateinit var pools: DatabasePools
    private lateinit var tx: ExposedTransactor

    @BeforeTest
    fun setUp() {
        TestDatabase.truncate()
        pools = DatabasePools(TestDatabase.settings)
        tx = ExposedTransactor(pools)
    }

    @AfterTest
    fun tearDown() = pools.close()

    private fun TxScope.insertKey(
        restaurantId: RestaurantId?,
        key: String,
    ) = ExposedTransactor
        .connection(this)
        .prepareStatement(
            "INSERT INTO idempotency_keys (scope, operation, key, restaurant_id, request_hash, created_at) " +
                "VALUES ('s', 'op', ?, ?, '\\x00', now())",
        ).use {
            it.setString(1, key)
            it.setObject(2, restaurantId?.value, java.sql.Types.OTHER)
            it.executeUpdate()
        }

    private fun TxScope.keys(): List<String> =
        ExposedTransactor.connection(this).prepareStatement("SELECT key FROM idempotency_keys ORDER BY key").use {
            it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }

    private fun TxScope.single(sql: String): String? =
        ExposedTransactor.connection(this).prepareStatement(sql).use {
            it.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
        }

    private fun seed() =
        runBlocking {
            tx.platformTransaction("test seed") {
                insertKey(a, "a1")
                insertKey(b, "b1")
                insertKey(null, "platform1")
            }
        }

    @Test
    fun `a tenant transaction sees only its restaurant's rows`() {
        seed()
        runBlocking {
            assertEquals(listOf("a1"), tx.inTransaction(a) { keys() })
            assertEquals(listOf("b1"), tx.inTransaction(b) { keys() })
            assertEquals(listOf("a1", "b1", "platform1"), tx.platformTransaction("test") { keys() })
        }
    }

    @Test
    fun `a tenant transaction can't write another restaurant's rows`() {
        val e =
            assertFailsWith<SQLException> {
                runBlocking { tx.inTransaction(a) { insertKey(b, "b2") } }
            }
        assertEquals("42501", e.sqlState, "row-level security WITH CHECK")
    }

    @Test
    fun `a transaction that never set the tenant sees 0 rows and can't write`() {
        seed()
        TestDatabase.connect(TestDatabase.app).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM idempotency_keys").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt(1))
                }
            }
            val e =
                assertFailsWith<SQLException> {
                    conn.createStatement().use {
                        it.executeUpdate(
                            "INSERT INTO idempotency_keys (scope, operation, key, restaurant_id, request_hash, created_at) " +
                                "VALUES ('s', 'op', 'x', '${a.value}', '\\x00', now())",
                        )
                    }
                }
            assertEquals("42501", e.sqlState)
            conn.rollback()
        }
    }

    @Test
    fun `app can't bypass RLS or change the schema, app_platform bypasses RLS`() {
        TestDatabase.connect(TestDatabase.app).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT rolname, rolbypassrls, rolsuper FROM pg_roles WHERE rolname IN ('app', 'app_platform')").use { rs ->
                    val roles =
                        generateSequence {
                            if (rs.next()) {
                                rs.getString(
                                    1,
                                ) to (rs.getBoolean(2) to rs.getBoolean(3))
                            } else {
                                null
                            }
                        }.toMap()
                    assertEquals(false to false, roles["app"])
                    assertEquals(true to false, roles["app_platform"])
                }
                for (ddl in listOf("CREATE TABLE ddl_probe (id int)", "DROP POLICY tenant_isolation ON idempotency_keys")) {
                    val e = assertFailsWith<SQLException>(ddl) { st.execute(ddl) }
                    assertEquals("42501", e.sqlState, ddl)
                    conn.rollback()
                }
            }
        }
    }

    @Test
    fun `the tenant setting ends with its transaction`() {
        runBlocking {
            assertEquals(a.toString(), tx.inTransaction(a) { single("SELECT current_setting('app.restaurant_id', true)") })
            // A platform transaction on the same server sets the tenant to empty, never inherits one.
            assertEquals("", tx.platformTransaction("test") { single("SELECT current_setting('app.restaurant_id', true)") })
        }
    }

    @Test
    fun `nested calls join the open transaction, another tenant's can't`() {
        runBlocking {
            tx.inTransaction(a) {
                val outer = single("SELECT txid_current()")
                assertEquals(outer, tx.inTransaction(a) { single("SELECT txid_current()") })
                assertFailsWith<IllegalStateException> { tx.inTransaction(b) { } }
                assertFailsWith<IllegalStateException> { tx.platformTransaction("test") { } }
            }
        }
    }

    @Test
    fun `an exception rolls back everything, and the work runs once`() {
        var runs = 0
        assertFailsWith<SQLException> {
            runBlocking {
                tx.inTransaction(a) {
                    runs++
                    insertKey(a, "a1")
                    insertKey(a, "a1") // primary key violation
                }
            }
        }
        assertEquals(1, runs, "Exposed must not retry the transaction")
        runBlocking { assertTrue(tx.inTransaction(a) { keys() }.isEmpty()) }
    }

    @Test
    fun `before-commit actions run in order, and not when the block fails`() {
        val ran = mutableListOf<String>()
        runBlocking {
            tx.inTransaction(a) {
                beforeCommit { ran += "first" }
                beforeCommit {
                    ran += "second"
                    beforeCommit { ran += "added by second" }
                }
                ran += "block"
            }
        }
        assertEquals(listOf("block", "first", "second", "added by second"), ran)

        ran.clear()
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                tx.inTransaction(a) {
                    beforeCommit { ran += "hook" }
                    throw IllegalArgumentException("fail")
                }
            }
        }
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `a statement over the timeout is 503 service_unavailable`() {
        val slow = DatabasePools(TestDatabase.settings.copy(statementTimeout = 200.milliseconds))
        slow.use {
            val e =
                assertFailsWith<DomainError.ServiceUnavailable> {
                    runBlocking {
                        ExposedTransactor(
                            it,
                        ).inTransaction(a) { single("SELECT pg_sleep(1)") }
                    }
                }
            assertEquals(DomainError.DEFAULT_RETRY_AFTER_SECONDS, e.retryAfterSeconds)
        }
    }

    @Test
    fun `an unreachable database is 503 service_unavailable`() {
        val nowhere = JdbcTarget("jdbc:postgresql://127.0.0.1:1/none", "app", "x")
        val down = DatabasePools(TestDatabase.settings.copy(app = nowhere, connectionTimeout = 300.milliseconds))
        down.use {
            assertFailsWith<DomainError.ServiceUnavailable> { runBlocking { ExposedTransactor(it).inTransaction(a) { keys() } } }
        }
    }

    @Test
    fun `the driver gets its settings`() {
        runBlocking {
            for (open in listOf<
                suspend (
                    suspend TxScope.() -> Unit,
                ) -> Unit,
            >({ tx.inTransaction(a, it) }, { tx.platformTransaction("test", it) })) {
                open {
                    val pg = ExposedTransactor.connection(this).unwrap(PGConnection::class.java)
                    assertEquals(0, pg.prepareThreshold)
                    assertEquals("5s", single("SHOW statement_timeout"))
                }
            }
        }
    }

    @Test
    fun `a failed statement's parameters stay out of the exception`() {
        val e =
            assertFailsWith<SQLException> {
                runBlocking {
                    // PostgreSQL leaves the key's values out of its error detail when RLS applies to
                    // the caller, so this runs where it doesn't, as a global table will for `app`.
                    tx.platformTransaction("test") {
                        // Through Exposed, whose ExposedSQLException.toString() appends the SQL.
                        repeat(2) {
                            TransactionManager.current().exec(
                                "INSERT INTO idempotency_keys (scope, operation, key, restaurant_id, request_hash, created_at) " +
                                    "VALUES ('s', 'op', ?, ?::uuid, '\\x00', now())",
                                listOf(TextColumnType() to "someone@example.com", TextColumnType() to a.toString()),
                            )
                        }
                    }
                }
            }
        // Exposed's wrapper prints the SQL with its values; pgjdbc's "Detail:" line quotes the key.
        val printed = e.stackTraceToString()
        assertFalse("someone@example.com" in printed, printed)
    }
}
