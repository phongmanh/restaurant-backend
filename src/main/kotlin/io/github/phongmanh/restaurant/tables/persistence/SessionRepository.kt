package io.github.phongmanh.restaurant.tables.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.domain.TableId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.instant
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.core.persistence.uuid
import io.github.phongmanh.restaurant.tables.domain.CloseReason
import io.github.phongmanh.restaurant.tables.domain.SessionStatus
import io.github.phongmanh.restaurant.tables.domain.TableSession
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant

/** How a session row is locked (tables §7): to change it, or so a close can't overlap (an order, a call). */
enum class SessionLock(
    val sql: String,
) {
    /** Move and close. */
    UPDATE("FOR UPDATE"),

    /**
     * Work that adds to an open session and then touches it (`last_activity_at`). Not `FOR SHARE`:
     * two transactions that each hold a share lock and then update the row wait for each other,
     * a deadlock. `FOR NO KEY UPDATE` still blocks a close (`FOR UPDATE`) until the commit.
     */
    ADD("FOR NO KEY UPDATE"),
}

/** What the guest authentication reads on every guest call (§3.2), in one query. */
data class GuestSessionRow(
    val status: SessionStatus,
    val tableId: TableId,
    val restaurantSuspended: Boolean,
)

/**
 * `table_sessions` (tables §3, §5). Every method takes the restaurant and filters by it.
 */
class SessionRepository {
    fun find(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: TableSessionId,
    ): TableSession? =
        connection(tx).prepareStatement("$SELECT WHERE s.restaurant_id = ? AND s.id = ?").use {
            it.bind(restaurantId.value, id.value).firstOrNull(::session)
        }

    /** The sessions named; another restaurant's are missing. */
    fun findAll(
        tx: TxScope,
        restaurantId: RestaurantId,
        ids: Collection<TableSessionId>,
    ): List<TableSession> {
        if (ids.isEmpty()) return emptyList()
        return connection(tx).prepareStatement("$SELECT WHERE s.restaurant_id = ? AND s.id = ANY(?::uuid[])").use {
            it.bind(restaurantId.value, ids.map(TableSessionId::toString)).list(::session)
        }
    }

    /**
     * The session with its row locked until the transaction ends. The lock is taken by a query on
     * `table_sessions` alone, then the row is read: a locking query with a join re-checks the join
     * after it waits, so a session another transaction just moved would drop out of it (a `404`
     * where the loser of two moves should get `412`).
     */
    fun lock(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: TableSessionId,
        lock: SessionLock,
    ): TableSession? {
        connection(tx).prepareStatement("SELECT id FROM table_sessions WHERE restaurant_id = ? AND id = ? ${lock.sql}").use {
            it.bind(restaurantId.value, id.value).firstOrNull { true }
        } ?: return null
        return find(tx, restaurantId, id)
    }

    /** The table's open session, if it has one. */
    fun openAt(
        tx: TxScope,
        restaurantId: RestaurantId,
        tableId: TableId,
    ): TableSession? =
        connection(tx).prepareStatement("$SELECT WHERE s.restaurant_id = ? AND s.table_id = ? AND s.status = 'open'").use {
            it.bind(restaurantId.value, tableId.value).firstOrNull(::session)
        }

    /** The restaurant's open sessions, for the floor view (§6) and `SessionAccess.openSessionsFor` (§7). */
    fun open(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): List<TableSession> =
        connection(tx).prepareStatement("$SELECT WHERE s.restaurant_id = ? AND s.status = 'open' ORDER BY s.opened_at, s.id").use {
            it.bind(restaurantId.value).list(::session)
        }

    /**
     * Opens a session at the table unless one is open there. Two first scans at the same moment
     * both get here: the partial unique index lets one insert through, and the other waits for it,
     * then inserts nothing (§3.1). Returns whether this call created it.
     */
    fun openIfNone(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: TableSessionId,
        tableId: TableId,
        now: Instant,
        openedBy: StaffId? = null,
    ): Boolean =
        connection(tx)
            .prepareStatement(
                "INSERT INTO table_sessions (id, restaurant_id, table_id, status, opened_at, opened_by, last_activity_at) " +
                    "VALUES (?, ?, ?, 'open', ?, ?::uuid, ?) ON CONFLICT (table_id) WHERE status = 'open' DO NOTHING",
            ).use { it.bind(id.value, restaurantId.value, tableId.value, now, openedBy?.value, now).executeUpdate() } == 1

    /** Sets `last_activity_at` (§3.5): a join, an order, a waiter call. */
    fun touch(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: TableSessionId,
        now: Instant,
    ) {
        connection(tx)
            .prepareStatement(
                "UPDATE table_sessions SET last_activity_at = greatest(last_activity_at, ?) WHERE restaurant_id = ? AND id = ?",
            ).use { it.bind(now, restaurantId.value, id.value).executeUpdate() }
    }

    /**
     * Moves an open session to [tableId] and increases its version. If another session opened there
     * meanwhile, the unique index refuses it: `409 table_occupied` (§3.4).
     */
    fun move(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: TableSessionId,
        tableId: TableId,
        now: Instant,
    ) {
        try {
            connection(tx)
                .prepareStatement(
                    "UPDATE table_sessions SET table_id = ?, last_activity_at = greatest(last_activity_at, ?), version = version + 1 " +
                        "WHERE restaurant_id = ? AND id = ? AND status = 'open'",
                ).use { it.bind(tableId.value, now, restaurantId.value, id.value).executeUpdate() }
        } catch (e: SQLException) {
            if (e.sqlState == TableRepository.UNIQUE_VIOLATION) throw DomainError.TableOccupied()
            throw e
        }
    }

    /** Closes an open session; returns its new version, or `null` if it wasn't open. [by] `null` is the system. */
    fun close(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: TableSessionId,
        reason: CloseReason,
        now: Instant,
        by: StaffId? = null,
    ): Int? =
        connection(tx)
            .prepareStatement(
                "UPDATE table_sessions SET status = 'closed', closed_at = ?, closed_by = ?::uuid, close_reason = ?, " +
                    "version = version + 1 WHERE restaurant_id = ? AND id = ? AND status = 'open' RETURNING version",
            ).use { it.bind(now, by?.value, reason.value, restaurantId.value, id.value).firstOrNull { rs -> rs.getInt(1) } }

    /** The guest authentication's one query (§3.2 step 2), with the restaurant's status. */
    fun guestRow(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: TableSessionId,
    ): GuestSessionRow? =
        connection(tx)
            .prepareStatement(
                "SELECT s.status, s.table_id, r.status AS restaurant_status FROM table_sessions s " +
                    "JOIN restaurants r ON r.id = s.restaurant_id WHERE s.restaurant_id = ? AND s.id = ?",
            ).use {
                it.bind(restaurantId.value, id.value).firstOrNull { rs ->
                    GuestSessionRow(
                        status = SessionStatus.of(rs.getString("status")),
                        tableId = TableId(rs.uuid("table_id")!!),
                        restaurantSuspended = rs.getString("restaurant_status") == SUSPENDED,
                    )
                }
            }

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)

    private companion object {
        const val SUSPENDED = "suspended"

        const val SELECT =
            "SELECT s.id, s.table_id, t.number, s.status, s.opened_at, s.last_activity_at, s.closed_at, s.version " +
                "FROM table_sessions s JOIN dining_tables t ON t.restaurant_id = s.restaurant_id AND t.id = s.table_id"

        fun session(rs: ResultSet) =
            TableSession(
                id = TableSessionId(rs.uuid("id")!!),
                tableId = TableId(rs.uuid("table_id")!!),
                tableNumber = rs.getString("number"),
                status = SessionStatus.of(rs.getString("status")),
                openedAt = rs.instant("opened_at")!!,
                lastActivityAt = rs.instant("last_activity_at")!!,
                closedAt = rs.instant("closed_at"),
                version = rs.getInt("version"),
            )
    }
}

/**
 * The auto-close job's reads of `table_sessions` (tables §3.5). [idleRestaurants] is the one
 * platform-wide search of this feature; the sessions are then locked in each restaurant's own
 * transaction.
 */
class IdleSessionRepository {
    /** The restaurants with open sessions idle since before [before] (§3.5). A platform transaction. */
    fun idleRestaurants(
        tx: TxScope,
        before: Instant,
        limit: Int,
    ): List<RestaurantId> =
        connection(tx)
            .prepareStatement("SELECT DISTINCT restaurant_id FROM table_sessions WHERE status = 'open' AND last_activity_at < ? LIMIT ?")
            .use { it.bind(before, limit).list { rs -> RestaurantId(rs.uuid("restaurant_id")!!) } }

    /**
     * Up to [limit] of the restaurant's open sessions idle since before [before], locked `FOR UPDATE`.
     * Rows another transaction holds (a guest ordering, a staff member closing) are skipped this run.
     */
    fun lockIdle(
        tx: TxScope,
        restaurantId: RestaurantId,
        before: Instant,
        limit: Int,
    ): List<TableSessionId> =
        connection(tx)
            .prepareStatement(
                "SELECT id FROM table_sessions WHERE restaurant_id = ? AND status = 'open' AND last_activity_at < ? " +
                    "ORDER BY last_activity_at LIMIT ? FOR UPDATE SKIP LOCKED",
            ).use { it.bind(restaurantId.value, before, limit).list { rs -> TableSessionId(rs.uuid("id")!!) } }

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)
}
