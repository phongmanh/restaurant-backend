package io.github.phongmanh.restaurant.core.persistence

import io.github.phongmanh.restaurant.core.application.Actor
import io.github.phongmanh.restaurant.core.application.ActorType
import io.github.phongmanh.restaurant.core.application.AuditChains
import io.github.phongmanh.restaurant.core.application.AuditEvent
import io.github.phongmanh.restaurant.core.application.AuditRecord
import io.github.phongmanh.restaurant.core.application.ChainedAuditLog
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.support.TestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.SQLException
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The audit log's chain and grants: the core parts of IT-CORE-05 (core-and-auth §12, §15). The
 * auth actions are checked with the auth routes in step 3.
 */
class AuditLogTest {
    private val a = RestaurantId(UuidV7.next())
    private val b = RestaurantId(UuidV7.next())
    private val repository = AuditRepository()
    private val audit = ChainedAuditLog(repository, Clock.systemUTC(), UuidV7)
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

    private fun event(
        restaurantId: RestaurantId?,
        action: String = "test.action",
        request: RequestMeta? = null,
    ) = AuditEvent(
        restaurantId = restaurantId,
        actor = Actor(ActorType.STAFF, UuidV7.next()),
        action = action,
        targetType = "order",
        targetId = UuidV7.next(),
        details = JsonObject(mapOf("count" to JsonPrimitive(3), "reason" to JsonPrimitive("a \"quoted\" ü"))),
        request = request,
    )

    private fun chain(restaurantId: RestaurantId?): List<AuditRecord> =
        runBlocking { tx.platformTransaction("test read") { repository.read(this, restaurantId, 0, 1000) } }

    @Test
    fun `entries form a chain that verifies, per restaurant and for the platform`() {
        runBlocking {
            tx.inTransaction(a) {
                audit.record(this, event(a, "first"))
                audit.record(this, event(a, "second"))
            }
            tx.inTransaction(a) { audit.record(this, event(a, "third")) }
            tx.inTransaction(b) { audit.record(this, event(b)) }
            // A platform transaction may write to the platform chain and to a restaurant's.
            tx.platformTransaction("test") {
                audit.record(this, event(null, "platform"))
                audit.record(this, event(a, "fourth"))
            }
        }
        val chainA = chain(a)
        assertEquals(listOf("first", "second", "third", "fourth"), chainA.map { it.action })
        assertEquals(listOf(1L, 2, 3, 4), chainA.map { it.chainSeq })
        assertNull(AuditChains.verify(chainA))
        assertNull(AuditChains.verify(chain(b)))
        assertEquals(listOf("platform"), chain(null).map { it.action })
        assertNull(AuditChains.verify(chain(null)))
    }

    @Test
    fun `changing or removing an entry breaks verification from that entry on`() {
        runBlocking { repeat(4) { tx.inTransaction(a) { audit.record(this, event(a)) } } }
        TestDatabase.superuser().use { conn ->
            conn.prepareStatement("UPDATE audit_events SET details = '{\"count\": 4}' WHERE restaurant_id = ? AND chain_seq = 2").use {
                it.setObject(1, a.value)
                it.executeUpdate()
            }
        }
        assertEquals(2L, AuditChains.verify(chain(a)))

        TestDatabase.superuser().use { conn ->
            conn.prepareStatement("DELETE FROM audit_events WHERE restaurant_id = ? AND chain_seq IN (2, 3)").use {
                it.setObject(1, a.value)
                it.executeUpdate()
            }
        }
        assertEquals(4L, AuditChains.verify(chain(a)), "a gap is caught at the entry after it")
    }

    @Test
    fun `app and app_platform can't update or delete entries`() {
        runBlocking { tx.inTransaction(a) { audit.record(this, event(a)) } }
        val attempts =
            listOf(TestDatabase.app, TestDatabase.platform).flatMap { role ->
                listOf("UPDATE audit_events SET action = 'x'", "DELETE FROM audit_events").map { role to it }
            }
        for ((role, sql) in attempts) {
            TestDatabase.connect(role).use { conn ->
                conn.createStatement().use { st ->
                    st.execute("SELECT set_config('app.restaurant_id', '${a.value}', true)")
                    val e = assertFailsWith<SQLException>("${role.user}: $sql") { st.executeUpdate(sql) }
                    assertEquals("42501", e.sqlState, "${role.user}: $sql")
                }
            }
        }
    }

    @Test
    fun `a rolled-back transaction leaves no entry and doesn't move the head`() {
        runBlocking { tx.inTransaction(a) { audit.record(this, event(a)) } }
        assertFailsWith<IllegalStateException> {
            runBlocking {
                tx.inTransaction(a) {
                    audit.record(this, event(a))
                    error("the change failed")
                }
            }
        }
        runBlocking { tx.inTransaction(a) { audit.record(this, event(a)) } }
        assertEquals(listOf(1L, 2), chain(a).map { it.chainSeq })
        assertNull(AuditChains.verify(chain(a)))
    }

    @Test
    fun `concurrent appends to one chain stay gapless`() {
        runBlocking(Dispatchers.Default) {
            (1..20).map { async { tx.inTransaction(a) { audit.record(this, event(a)) } } }.awaitAll()
        }
        val entries = chain(a)
        assertEquals((1L..20).toList(), entries.map { it.chainSeq })
        assertNull(AuditChains.verify(entries))
    }

    @Test
    fun `request data is kept in a form that reads back the same`() {
        runBlocking {
            for (ip in listOf("203.0.113.7", "2001:db8::1", "::ffff:198.51.100.2", "not-an-ip", null)) {
                tx.inTransaction(
                    a,
                ) { audit.record(this, event(a, request = RequestMeta("req-1", ip, "Mozilla/5.0\u0000 " + "x".repeat(300)))) }
            }
        }
        val entries = chain(a)
        assertNull(AuditChains.verify(entries))
        assertEquals(listOf("203.0.113.7", "2001:db8:0:0:0:0:0:1", "198.51.100.2", null, null), entries.map { it.ip })
        assertTrue(entries.all { it.userAgent!!.length == 200 && '\u0000' !in it.userAgent })
    }

    @Test
    fun `a tenant transaction can't record to another restaurant's chain`() {
        assertFailsWith<IllegalArgumentException> { runBlocking { tx.inTransaction(a) { audit.record(this, event(b)) } } }
        assertFailsWith<IllegalArgumentException> { runBlocking { tx.inTransaction(a) { audit.record(this, event(null)) } } }
    }

    @Test
    fun `details with a fraction are refused`() {
        val fraction = event(a).copy(details = JsonObject(mapOf("ratio" to JsonPrimitive(0.5))))
        assertFailsWith<IllegalArgumentException> { runBlocking { tx.inTransaction(a) { audit.record(this, fraction) } } }
    }
}
