package io.github.phongmanh.restaurant.orders.application

import io.github.phongmanh.restaurant.core.application.AuditEvent
import io.github.phongmanh.restaurant.core.application.AuditLog
import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.Rfc3339
import io.github.phongmanh.restaurant.orders.domain.LineStatus
import io.github.phongmanh.restaurant.orders.domain.Order
import io.github.phongmanh.restaurant.orders.domain.OrderId
import io.github.phongmanh.restaurant.orders.domain.OrderLineId
import io.github.phongmanh.restaurant.orders.domain.OrderStatus
import io.github.phongmanh.restaurant.orders.domain.Reason
import io.github.phongmanh.restaurant.orders.domain.Transitions
import io.github.phongmanh.restaurant.orders.persistence.BillRepository
import io.github.phongmanh.restaurant.orders.persistence.LineRejection
import io.github.phongmanh.restaurant.orders.persistence.OrderRepository
import io.github.phongmanh.restaurant.orders.persistence.OrderRow
import io.github.phongmanh.restaurant.orders.persistence.StatusChange
import io.github.phongmanh.restaurant.realtime.application.EventAudience
import io.github.phongmanh.restaurant.realtime.application.EventLog
import io.github.phongmanh.restaurant.realtime.application.EventSubject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock

/** One line of one order, as the line-rejection path names it. */
data class LineRef(
    val orderId: OrderId,
    val lineId: OrderLineId,
)

/**
 * Changing an order (orders §5): staff steps (FR-012), the guest's cancel (FR-006) and rejecting one
 * line (FR-015). A step that takes money off the bill locks the bill row before the order row, as
 * every write does (session → bill → order rows → event counter → audit chain head), so a close and
 * a cancel can't deadlock.
 */
class OrderStatusService(
    private val transactor: Transactor,
    private val orders: OrderRepository,
    private val bills: BillRepository,
    private val query: OrderQueryService,
    private val events: EventLog,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /**
     * One staff step (orders §5.1). Not in the restaurant → `404`; stale `If-Match` → `412`; a step
     * that isn't allowed from the order's status → `409 invalid_transition`. Two staff with one
     * version: one wins, the other gets `412`.
     */
    suspend fun change(
        step: OrderStep,
        id: OrderId,
        version: Int,
        reason: Reason?,
        request: RequestMeta,
    ): Order =
        transactor.inTransaction(step.restaurantId) {
            val rid = step.restaurantId
            val order = lockForChange(this, rid, id, takesMoney = !step.to.live)
            if (order.version != version) throw DomainError.PreconditionFailed()
            if (!Transitions.allowed(order.status, step.to)) throw DomainError.InvalidTransition()
            moveTo(this, rid, order, StatusChange(step.to, reason, Rfc3339.truncate(clock.instant()), step.staffId))
            val details =
                mutableMapOf<String, JsonElement>("from" to JsonPrimitive(order.status.value), "to" to JsonPrimitive(step.to.value))
            reason?.let { details["reason_code"] = JsonPrimitive(it.code.value) }
            audit.record(
                this,
                AuditEvent(rid, step.actor, "order.status_changed", OrderTargets.ORDER, id.value, JsonObject(details), request),
            )
            query.order(this, rid, id)!!
        }

    /**
     * The guest's cancel (orders §5.2): `placed` → `cancelled`; already cancelled → the order as it
     * is, so a retry gives the same result without an `Idempotency-Key` (CR-005); accepted or later
     * → `409 order_not_cancellable`. Guests aren't in the audit log (NFR-SEC-03 covers staff).
     */
    suspend fun cancelByGuest(
        guest: Caller.Guest,
        id: OrderId,
    ): Order =
        transactor.inTransaction(guest.restaurantId) {
            val rid = guest.restaurantId
            val found =
                orders.find(this, rid, listOf(id)).singleOrNull()?.takeIf { it.sessionId == guest.sessionId }
                    ?: throw DomainError.NotFound()
            if (found.status == OrderStatus.CANCELLED) return@inTransaction query.order(this, rid, id)!!
            if (found.status != OrderStatus.PLACED) throw DomainError.OrderNotCancellable()
            val order = lockForChange(this, rid, id, takesMoney = true)
            // Re-checked under the lock: the kitchen may have accepted it, or another tap cancelled it, meanwhile.
            if (order.status == OrderStatus.PLACED) {
                moveTo(this, rid, order, StatusChange(OrderStatus.CANCELLED, null, Rfc3339.truncate(clock.instant()), null))
            } else if (order.status != OrderStatus.CANCELLED) {
                throw DomainError.OrderNotCancellable()
            }
            query.order(this, rid, id)!!
        }

    /**
     * Rejects one line (orders §5.3): the order must be `accepted` or `preparing` and the line
     * active, otherwise `409 line_not_rejectable`. The line leaves the order's and the bill's
     * totals. Rejecting the last active line rejects the order too, with the same reason (§15 Q3).
     */
    suspend fun rejectLine(
        rejecter: LineRejecter,
        ref: LineRef,
        version: Int,
        reason: Reason,
        request: RequestMeta,
    ): Order =
        transactor.inTransaction(rejecter.restaurantId) {
            val rid = rejecter.restaurantId
            val id = ref.orderId
            val lineId = ref.lineId
            val order = lockForChange(this, rid, id, takesMoney = true)
            val lines = orders.lines(this, rid, listOf(id))[id].orEmpty()
            val line = lines.firstOrNull { it.id == lineId } ?: throw DomainError.NotFound()
            if (order.version != version) throw DomainError.PreconditionFailed()
            val rejectable = order.status == OrderStatus.ACCEPTED || order.status == OrderStatus.PREPARING
            if (!rejectable || line.status != LineStatus.ACTIVE) throw DomainError.LineNotRejectable()
            val now = Rfc3339.truncate(clock.instant())
            val last = lines.count { it.status == LineStatus.ACTIVE } == 1
            orders.rejectLine(this, rid, id, line, LineRejection(reason, now, rejecter.staffId))
            if (last) orders.changeStatus(this, rid, id, StatusChange(OrderStatus.REJECTED, reason, now, rejecter.staffId))
            bills.change(this, rid, order.sessionId, -line.lineTotal, if (last) -1 else 0)
            val details =
                mapOf(
                    "line_id" to JsonPrimitive(lineId.toString()),
                    "amount" to JsonPrimitive(line.lineTotal.toString()),
                    "reason_code" to JsonPrimitive(reason.code.value),
                    "order_rejected" to JsonPrimitive(last),
                )
            audit.record(
                this,
                AuditEvent(rid, rejecter.actor, "order.line_rejected", OrderTargets.ORDER, id.value, JsonObject(details), request),
            )
            val changed = query.order(this, rid, id)!!
            val subject = EventSubject(OrderEvents.ORDER_AGGREGATE, id.value, changed.version)
            events.append(this, rid, OrderEvents.ORDER_LINE_REJECTED, subject, EventAudience.STAFF_AND_SESSION, order.sessionId)
            if (last) events.append(this, rid, OrderEvents.ORDER_STATUS_CHANGED, subject, EventAudience.STAFF_AND_SESSION, order.sessionId)
            changed
        }

    /**
     * Locks the order for a change; with [takesMoney] the session's bill row first (lock order).
     * Not in the restaurant → `404`.
     */
    private fun lockForChange(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: OrderId,
        takesMoney: Boolean,
    ): OrderRow {
        if (takesMoney) {
            val sessionId = orders.find(tx, restaurantId, listOf(id)).singleOrNull()?.sessionId ?: throw DomainError.NotFound()
            bills.lock(tx, restaurantId, sessionId)
        }
        return orders.lock(tx, restaurantId, id) ?: throw DomainError.NotFound()
    }

    /** Moves a locked order on; `rejected` or `cancelled` takes it off the bill. Writes `OrderStatusChanged`. */
    private fun moveTo(
        tx: TxScope,
        restaurantId: RestaurantId,
        order: OrderRow,
        change: StatusChange,
    ) {
        orders.changeStatus(tx, restaurantId, order.id, change)
        if (!change.to.live) bills.change(tx, restaurantId, order.sessionId, -order.total, -1)
        val subject = EventSubject(OrderEvents.ORDER_AGGREGATE, order.id.value, order.version + 1)
        events.append(tx, restaurantId, OrderEvents.ORDER_STATUS_CHANGED, subject, EventAudience.STAFF_AND_SESSION, order.sessionId)
    }
}
