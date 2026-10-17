package io.github.phongmanh.restaurant.system

import io.github.phongmanh.restaurant.core.application.AuditChains
import io.github.phongmanh.restaurant.core.application.AuditRecord
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.config.AppConfig
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.persistence.AuditRepository
import io.github.phongmanh.restaurant.core.persistence.DatabasePools
import io.github.phongmanh.restaurant.core.persistence.DatabaseSettings
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.JdbcTarget
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * test-plan §4 ST-13 (NFR-SEC-03) on staging: audited actions leave their entries, every chain in
 * Neon verifies with the service's own check (core-and-auth §12), and the service's roles can't
 * change or delete an entry. CI proves the same rules on a test database (IT-CORE-05); this run
 * proves them on the deployed database, its grants and the rows the deployed service wrote.
 *
 * The database parts connect to Neon with the settings in `deploy/staging/.env.staging`, as the
 * seed does, and only read: the grant checks run statements that match no row, in a transaction
 * that is rolled back.
 */
class AuditTrailTest {
    private val a = Staging.a
    private val api = Staging.api
    private val audit = AuditRepository()

    @Test
    fun `ST-13 a member's invitation, sign-ins and deactivation are each audited, with the request's ID`() {
        val member = a.invite("st13", "waiter")
        val failed = api.call("POST", "/v1/auth/sign-in", credentials(member.email, "not-the-password"))
        assertEquals(UNAUTHORIZED, failed.status)
        val failedRequest = failed.json.str("request_id")
        Staging.flows.signIn(member.email, requireNotNull(member.password))
        api.expect(OK, "POST", "/v1/staff/members/${member.staffId}/deactivate", token = a.manager)

        val entries =
            listOf("target_id", "actor_id")
                .flatMap { api.expect(OK, "GET", "/v1/staff/audit-events?$it=${member.staffId}", token = a.manager).arrOf("items") }
                .distinctBy { it.str("id") }
        val actions = entries.map { it.str("action") }.toSet()
        for (action in AUDITED) assertTrue(action in actions, "the audit log has $action (it has $actions)")
        val failure = entries.first { it.str("action") == "auth.sign_in.failed" }
        assertEquals(failedRequest, failure.str("request_id"), "the entry carries the request's ID, as the problem body does")
        println("[ST-13] ${entries.size} entries for one member: ${actions.sorted()}")
    }

    @Test
    fun `ST-13 every audit chain on staging verifies, up to its head`() {
        DatabasePools(settings()).use { pools ->
            val tx = ExposedTransactor(pools)
            val heads = runBlocking { tx.platformTransaction("ST-13 reads the chain heads") { heads(this) } }
            assertTrue(
                heads.any { it.chainKey == PLATFORM } && heads.any { it.chainKey.toString() == a.id },
                "the platform's and A's chains",
            )
            val total = heads.sumOf { verify(tx, it) }
            println("[ST-13] ${heads.size} chains, $total entries, every one verified")
        }
    }

    /** Checks one chain from its first entry, a page at a time; returns its length. */
    private fun verify(
        tx: ExposedTransactor,
        head: Head,
    ): Long {
        val restaurantId = head.chainKey.takeUnless { it == PLATFORM }?.let(::RestaurantId)
        var last: AuditRecord? = null
        var count = 0L
        do {
            val after = last
            val page =
                runBlocking { tx.platformTransaction("ST-13 reads a chain") { audit.read(this, restaurantId, after?.chainSeq ?: 0, PAGE) } }
            val broken = AuditChains.verify(page, after?.chainSeq ?: 0, after?.hash ?: AuditChains.GENESIS)
            assertNull(broken, "chain ${head.chainKey} breaks at entry $broken")
            last = page.lastOrNull() ?: after
            count += page.size
        } while (page.isNotEmpty())
        val end = requireNotNull(last) { "chain ${head.chainKey} has a head and no entries" }
        // An entry appended after the heads were read (a cron run) leaves the chain past its head.
        assertTrue(end.chainSeq >= head.lastSeq, "chain ${head.chainKey} reaches its head (${head.lastSeq}), ends at ${end.chainSeq}")
        if (end.chainSeq == head.lastSeq) {
            assertTrue(MessageDigest.isEqual(head.lastHash, end.hash), "chain ${head.chainKey}: the head holds the last entry's hash")
        }
        return count
    }

    private fun heads(tx: TxScope): List<Head> =
        ExposedTransactor.connection(tx).prepareStatement("SELECT chain_key, last_seq, last_hash FROM audit_chain_heads").use {
            it.executeQuery().use { rs ->
                buildList { while (rs.next()) add(Head(rs.getObject(1, UUID::class.java), rs.getLong(2), rs.getBytes(3))) }
            }
        }

    @Test
    fun `ST-13 neither of the service's roles can change or delete an audit entry`() {
        val settings = settings()
        for ((role, target) in listOf("app" to settings.app, "app_platform" to settings.platform)) {
            for (sql in STATEMENTS) {
                val e = assertFailsWith<SQLException>("$role: $sql") { attempt(target, sql) }
                assertEquals(INSUFFICIENT_PRIVILEGE, e.sqlState, "$role: $sql is refused for want of the grant")
            }
        }
    }

    /** Runs [sql] in a transaction scoped to restaurant A, then rolls it back. */
    private fun attempt(
        target: JdbcTarget,
        sql: String,
    ) {
        DriverManager.getConnection(target.jdbcUrl, target.user, target.password).use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("SELECT set_config('app.restaurant_id', ?, true)").use {
                    it.setString(1, a.id)
                    it.execute()
                }
                conn.createStatement().use { it.executeUpdate(sql) }
            } finally {
                conn.rollback()
            }
        }
    }

    private fun settings(): DatabaseSettings = AppConfig.load(ApplicationConfig("application.yaml")).database

    private fun credentials(
        email: String,
        password: String,
    ) = buildJsonObject {
        put("email", email)
        put("password", password)
    }

    private class Head(
        val chainKey: UUID,
        val lastSeq: Long,
        val lastHash: ByteArray,
    )

    private companion object {
        /** The platform chain's key (core-and-auth §12). */
        val PLATFORM = UUID(0, 0)
        const val PAGE = 1_000
        const val INSUFFICIENT_PRIVILEGE = "42501"
        val AUDITED =
            listOf("staff.invited", "auth.invitation.accepted", "auth.sign_in.failed", "auth.sign_in.succeeded", "staff.deactivated")

        /** They match no row, so even a role that had the grant would change nothing. */
        val STATEMENTS = listOf("UPDATE audit_events SET action = action WHERE false", "DELETE FROM audit_events WHERE false")
    }
}
