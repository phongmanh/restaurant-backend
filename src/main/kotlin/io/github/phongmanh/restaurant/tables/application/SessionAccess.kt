package io.github.phongmanh.restaurant.tables.application

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.realtime.application.EventAudience
import io.github.phongmanh.restaurant.realtime.application.EventLog
import io.github.phongmanh.restaurant.realtime.application.EventSubject
import io.github.phongmanh.restaurant.tables.domain.CloseReason
import io.github.phongmanh.restaurant.tables.domain.SessionStatus
import io.github.phongmanh.restaurant.tables.domain.TableSession
import io.github.phongmanh.restaurant.tables.persistence.SessionLock
import io.github.phongmanh.restaurant.tables.persistence.SessionRepository
import io.github.phongmanh.restaurant.tables.persistence.WaiterCallRepository
import java.time.Instant

/**
 * The `SessionAccess` port (tables §7, orders §11): what `orders` does with a table session, inside
 * its own transaction. Every method takes the restaurant; another restaurant's session is `404`.
 *
 * **In plain words:** an order and a close can't overlap. An order holds the session row while it's
 * placed; a close waits for it, or the order waits for the close and is then refused.
 */
interface SessionAccess {
    /**
     * Locks the open session for work that adds to it (an order, a waiter call), until the commit.
     * Closed → `409 session_not_open` (FR-003). The lock is `FOR NO KEY UPDATE`, not the `FOR
     * SHARE` of §7: the same transaction then [touch]es the row, and two share locks that each wait
     * to update the row deadlock. A close (`FOR UPDATE`) still waits for it, as §7 intends.
     */
    fun requireOpenForUpdate(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
    ): TableSession

    /** Sets `last_activity_at` (§3.5); orders call it on every order. */
    fun touch(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
        now: Instant,
    )

    /** Locks the session `FOR UPDATE` before a close; closed → `409 session_not_open` (orders §7.1). */
    fun lockForClose(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
    ): TableSession

    /**
     * Closes the session (FR-013): its open waiter calls close with it, and `TableSessionClosed`
     * is written. Closing a closed session does nothing and returns `false`. [by] is `null` for
     * the system (§3.5).
     */
    fun close(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
        reason: CloseReason,
        now: Instant,
        by: StaffId? = null,
    ): Boolean

    /** The open sessions with their table numbers, for the floor view and the kitchen feed. */
    fun openSessionsFor(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): List<TableSession>
}

class DatabaseSessionAccess(
    private val sessions: SessionRepository,
    private val calls: WaiterCallRepository,
    private val events: EventLog,
) : SessionAccess {
    override fun requireOpenForUpdate(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
    ): TableSession = lockOpen(tx, restaurantId, sessionId, SessionLock.ADD)

    override fun touch(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
        now: Instant,
    ) = sessions.touch(tx, restaurantId, sessionId, now)

    override fun lockForClose(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
    ): TableSession = lockOpen(tx, restaurantId, sessionId, SessionLock.UPDATE)

    override fun close(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
        reason: CloseReason,
        now: Instant,
        by: StaffId?,
    ): Boolean {
        val version = sessions.close(tx, restaurantId, sessionId, reason, now, by) ?: return false
        calls.closeWithSession(tx, restaurantId, sessionId)
        val subject = EventSubject(TableEvents.SESSION_AGGREGATE, sessionId.value, version)
        events.append(tx, restaurantId, TableEvents.SESSION_CLOSED, subject, EventAudience.STAFF_AND_SESSION, sessionId)
        return true
    }

    override fun openSessionsFor(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): List<TableSession> = sessions.open(tx, restaurantId)

    private fun lockOpen(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
        lock: SessionLock,
    ): TableSession {
        val session = sessions.lock(tx, restaurantId, sessionId, lock) ?: throw DomainError.NotFound()
        if (session.status != SessionStatus.OPEN) throw DomainError.SessionNotOpen()
        return session
    }
}

/** A session's bill so far (orders §11): how many orders, and the running total in whole đồng. */
data class BillSummary(
    val orderCount: Int,
    val total: Long,
)

/**
 * The `SessionSummaries` port, provided by `orders` (tables §6, orders §11): the bill summary of
 * each session, in one query. A session missing from the answer has no orders.
 */
fun interface SessionSummaries {
    fun of(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionIds: List<TableSessionId>,
    ): Map<TableSessionId, BillSummary>
}

/**
 * The `EmptyBills` port, provided by `orders` (tables §3.5, orders §11). Called by the auto-close
 * job after it locks the session: it closes the bill with total 0 if the session has no live
 * orders and answers `true`; otherwise it answers `false` and the session stays open.
 */
fun interface EmptyBills {
    fun closeIfEmpty(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
    ): Boolean
}

/**
 * Both ports until the orders feature (step 7) provides them. No order can exist before then, so
 * every session has no orders and an empty bill: this answers what the real ports would.
 */
object NoOrdersYet : SessionSummaries, EmptyBills {
    override fun of(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionIds: List<TableSessionId>,
    ): Map<TableSessionId, BillSummary> = emptyMap()

    override fun closeIfEmpty(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
    ): Boolean = true
}
