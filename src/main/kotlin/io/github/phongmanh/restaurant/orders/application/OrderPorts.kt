package io.github.phongmanh.restaurant.orders.application

import io.github.phongmanh.restaurant.core.application.RetentionTask
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.Rfc3339
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.persistence.tryAdvisoryLock
import io.github.phongmanh.restaurant.orders.domain.BillStatus
import io.github.phongmanh.restaurant.orders.domain.OrderLimits
import io.github.phongmanh.restaurant.orders.persistence.BillClosing
import io.github.phongmanh.restaurant.orders.persistence.BillRepository
import io.github.phongmanh.restaurant.orders.persistence.NoteRepository
import io.github.phongmanh.restaurant.realtime.application.EventAudience
import io.github.phongmanh.restaurant.realtime.application.EventLog
import io.github.phongmanh.restaurant.realtime.application.EventSubject
import io.github.phongmanh.restaurant.tables.application.BillSummary
import io.github.phongmanh.restaurant.tables.application.EmptyBills
import io.github.phongmanh.restaurant.tables.application.SessionSummaries
import java.time.Clock
import java.time.Duration

/**
 * The ports `orders` provides to `tables` (orders §11): the floor view's bill summaries, and the
 * auto-close job's check that a session has nothing to pay.
 */
class BillPorts(
    private val bills: BillRepository,
    private val days: BusinessDay,
    private val events: EventLog,
    private val clock: Clock,
) : SessionSummaries,
    EmptyBills {
    override fun of(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionIds: List<TableSessionId>,
    ): Map<TableSessionId, BillSummary> = bills.summaries(tx, restaurantId, sessionIds)

    /**
     * Called after the job locked the session (tables §3.5). No bill row: nothing was ordered, `true`.
     * A bill whose orders were all cancelled or rejected is closed with total 0 and no payment, so
     * an abandoned session leaves no open bill behind, and nothing goes to the sales ledger.
     * Any order still on the bill → `false`, and the session stays open: closing settles money.
     */
    override fun closeIfEmpty(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
    ): Boolean {
        val bill = bills.lock(tx, restaurantId, sessionId)
        return when {
            bill == null || bill.status == BillStatus.CLOSED -> {
                true
            }

            bill.orderCount > 0 -> {
                false
            }

            else -> {
                val now = Rfc3339.truncate(clock.instant())
                bills.close(tx, restaurantId, sessionId, BillClosing(payment = null, at = now, by = null, days.of(tx, restaurantId, now)))
                val subject = EventSubject(OrderEvents.BILL_AGGREGATE, sessionId.value, bill.version + 1)
                events.append(tx, restaurantId, OrderEvents.BILL_CLOSED, subject, EventAudience.STAFF)
                true
            }
        }
    }
}

/**
 * The orders part of the daily retention job (orders §6, requirements §5): guests' item notes and
 * staff reason notes are cleared 30 days after the session closes. Audit `details` never held them.
 *
 * A platform transaction finds the restaurants with notes to clear; each restaurant's are then
 * cleared in its own transaction under `pg_try_advisory_xact_lock`, in bounded batches.
 */
class NoteRetention(
    private val transactor: Transactor,
    private val notes: NoteRepository,
    private val clock: Clock,
) : RetentionTask {
    override val name: String = "order_notes"

    override suspend fun run(): Int {
        val closedBefore = clock.instant().minus(Duration.ofDays(OrderLimits.NOTE_RETENTION_DAYS))
        val restaurants =
            transactor.platformTransaction("retention: restaurants with order notes to clear") {
                notes.restaurantsWithNotes(this, closedBefore, RESTAURANTS_PER_RUN)
            }
        return restaurants.sumOf { rid ->
            transactor.inTransaction(rid) {
                if (!tryAdvisoryLock(this, "job:retention:order-notes:$rid")) return@inTransaction 0
                notes.clearNotes(this, rid, closedBefore, ROWS_PER_RESTAURANT)
            }
        }
    }

    private companion object {
        const val RESTAURANTS_PER_RUN = 500
        const val ROWS_PER_RESTAURANT = 5_000
    }
}
