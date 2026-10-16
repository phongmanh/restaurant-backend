package io.github.phongmanh.restaurant.tables.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
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
import io.github.phongmanh.restaurant.tables.domain.WaiterCall
import io.github.phongmanh.restaurant.tables.domain.WaiterCallId
import io.github.phongmanh.restaurant.tables.domain.WaiterCallKind
import io.github.phongmanh.restaurant.tables.domain.WaiterCallStatus
import java.sql.ResultSet
import java.time.Instant

/** A call to insert. */
data class NewWaiterCall(
    val id: WaiterCallId,
    val sessionId: TableSessionId,
    val kind: WaiterCallKind,
    val createdAt: Instant,
)

/** `waiter_calls` (tables §4, §5). Every method takes the restaurant and filters by it. */
class WaiterCallRepository {
    fun find(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: WaiterCallId,
    ): WaiterCall? =
        connection(tx).prepareStatement("$SELECT WHERE c.restaurant_id = ? AND c.id = ?").use {
            it.bind(restaurantId.value, id.value).firstOrNull(::call)
        }

    /**
     * The call a new tap of [kind] merges into (FR-008): the session's unacknowledged call of that
     * kind, or else its latest one made after [since].
     */
    fun mergeable(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
        kind: WaiterCallKind,
        since: Instant,
    ): WaiterCall? =
        connection(tx)
            .prepareStatement(
                "$SELECT WHERE c.restaurant_id = ? AND c.session_id = ? AND c.kind = ? AND (c.status = 'open' OR c.created_at > ?) " +
                    "ORDER BY (c.status = 'open') DESC, c.created_at DESC LIMIT 1",
            ).use { it.bind(restaurantId.value, sessionId.value, kind.value, since).firstOrNull(::call) }

    /**
     * A new open call, unless the session has one of that kind: the partial unique index makes two
     * taps at the same moment one call (§4). Returns whether this call created it.
     */
    fun insertIfNone(
        tx: TxScope,
        restaurantId: RestaurantId,
        call: NewWaiterCall,
    ): Boolean =
        connection(tx)
            .prepareStatement(
                "INSERT INTO waiter_calls (id, restaurant_id, session_id, kind, status, created_at) VALUES (?, ?, ?, ?, 'open', ?) " +
                    "ON CONFLICT (session_id, kind) WHERE status = 'open' DO NOTHING",
            ).use { it.bind(call.id.value, restaurantId.value, call.sessionId.value, call.kind.value, call.createdAt).executeUpdate() } == 1

    /** Marks an open call acknowledged by [by]; returns whether it was open. */
    fun acknowledge(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: WaiterCallId,
        by: StaffId,
        now: Instant,
    ): Boolean =
        connection(tx)
            .prepareStatement(
                "UPDATE waiter_calls SET status = 'acknowledged', acknowledged_at = ?, acknowledged_by = ? " +
                    "WHERE restaurant_id = ? AND id = ? AND status = 'open'",
            ).use { it.bind(now, by.value, restaurantId.value, id.value).executeUpdate() } == 1

    /** Closes the session's open calls with it (§4); returns how many. */
    fun closeWithSession(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
    ): Int =
        connection(tx)
            .prepareStatement(
                "UPDATE waiter_calls SET status = 'closed_with_session' WHERE restaurant_id = ? AND session_id = ? AND status = 'open'",
            ).use { it.bind(restaurantId.value, sessionId.value).executeUpdate() }

    /**
     * What the guest sees (§3.3): the session's latest call of each kind while it's open or
     * acknowledged, so the app can show "called" and then "a waiter is coming".
     */
    fun latestPerKind(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
    ): List<WaiterCall> =
        connection(tx)
            .prepareStatement(
                "SELECT * FROM (${SELECT.replaceFirst("SELECT ", "SELECT DISTINCT ON (c.kind) ")} " +
                    "WHERE c.restaurant_id = ? AND c.session_id = ? ORDER BY c.kind, c.created_at DESC) latest " +
                    "WHERE status <> 'closed_with_session' ORDER BY created_at",
            ).use { it.bind(restaurantId.value, sessionId.value).list(::call) }

    /** The calls of [status] in the restaurant's open sessions, oldest first (§4). */
    fun list(
        tx: TxScope,
        restaurantId: RestaurantId,
        status: WaiterCallStatus,
    ): List<WaiterCall> =
        connection(tx)
            .prepareStatement("$SELECT WHERE c.restaurant_id = ? AND c.status = ? AND s.status = 'open' ORDER BY c.created_at, c.id")
            .use { it.bind(restaurantId.value, status.value).list(::call) }

    /** How many open calls each of [sessionIds] has; sessions without any are left out. */
    fun openCounts(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionIds: List<TableSessionId>,
    ): Map<TableSessionId, Int> =
        connection(tx)
            .prepareStatement(
                "SELECT session_id, count(*) FROM waiter_calls WHERE restaurant_id = ? AND status = 'open' " +
                    "AND session_id = ANY(?::uuid[]) GROUP BY session_id",
            ).use {
                it.bind(restaurantId.value, sessionIds.map { id -> id.toString() }).list { rs ->
                    TableSessionId(rs.uuid("session_id")!!) to rs.getInt(2)
                }
            }.toMap()

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)

    private companion object {
        const val SELECT =
            "SELECT c.id, c.session_id, c.kind, c.status, c.created_at, c.acknowledged_at, s.table_id, t.number " +
                "FROM waiter_calls c JOIN table_sessions s ON s.restaurant_id = c.restaurant_id AND s.id = c.session_id " +
                "JOIN dining_tables t ON t.restaurant_id = s.restaurant_id AND t.id = s.table_id"

        fun call(rs: ResultSet) =
            WaiterCall(
                id = WaiterCallId(rs.uuid("id")!!),
                sessionId = TableSessionId(rs.uuid("session_id")!!),
                kind = WaiterCallKind.of(rs.getString("kind"))!!,
                status = WaiterCallStatus.of(rs.getString("status")),
                createdAt = rs.instant("created_at")!!,
                acknowledgedAt = rs.instant("acknowledged_at"),
                tableId = TableId(rs.uuid("table_id")!!),
                tableNumber = rs.getString("number"),
            )
    }
}
