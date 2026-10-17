package io.github.phongmanh.restaurant.orders.application

import io.github.phongmanh.restaurant.core.application.AuditEvent
import io.github.phongmanh.restaurant.core.application.AuditLog
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.FieldError
import io.github.phongmanh.restaurant.core.domain.IdGenerator
import io.github.phongmanh.restaurant.core.domain.Rfc3339
import io.github.phongmanh.restaurant.core.observability.ServiceMeters
import io.github.phongmanh.restaurant.menu.application.LineProblem
import io.github.phongmanh.restaurant.menu.application.LineProblemCode
import io.github.phongmanh.restaurant.menu.application.LineRequest
import io.github.phongmanh.restaurant.menu.application.MenuCatalog
import io.github.phongmanh.restaurant.menu.application.PricedLine
import io.github.phongmanh.restaurant.orders.domain.LineOption
import io.github.phongmanh.restaurant.orders.domain.LineStatus
import io.github.phongmanh.restaurant.orders.domain.Order
import io.github.phongmanh.restaurant.orders.domain.OrderId
import io.github.phongmanh.restaurant.orders.domain.OrderLine
import io.github.phongmanh.restaurant.orders.domain.OrderLineId
import io.github.phongmanh.restaurant.orders.domain.OrderStatus
import io.github.phongmanh.restaurant.orders.persistence.BillRepository
import io.github.phongmanh.restaurant.orders.persistence.NewOrder
import io.github.phongmanh.restaurant.orders.persistence.OrderRepository
import io.github.phongmanh.restaurant.realtime.application.EventAudience
import io.github.phongmanh.restaurant.realtime.application.EventLog
import io.github.phongmanh.restaurant.realtime.application.EventSubject
import io.github.phongmanh.restaurant.tables.application.SessionAccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.util.UUID

/** One line as the client sent it, checked for its form (orders §3.1). Never a price. */
data class LineInput(
    val itemId: UUID,
    val quantity: Int,
    val optionIds: List<UUID>,
    val note: String?,
)

/**
 * Placing an order (orders §3.1, §3.2; FR-003, FR-004, FR-014): by a guest of the session, or by a
 * waiter or manager for it. All lines or none; priced from the database, whatever the client sends.
 *
 * Runs in the transaction of the `Idempotency-Key` helper, so the key, the order and its stored
 * answer commit together. Locks, in the project's order: session → bill → order rows → event
 * counter → audit chain head.
 */
class PlaceOrderService(
    private val access: SessionAccess,
    private val catalog: MenuCatalog,
    private val bills: BillRepository,
    private val orders: OrderRepository,
    private val events: EventLog,
    private val audit: AuditLog,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val meters: ServiceMeters = ServiceMeters.none(),
) {
    fun place(
        tx: TxScope,
        placer: Placer,
        lines: List<LineInput>,
        request: RequestMeta?,
    ): Order {
        val rid = placer.restaurantId
        val sid = placer.sessionId
        // Closed meanwhile → 409 session_not_open; a close waits for this order, or this for it (tables §7).
        val session = access.requireOpenForUpdate(tx, rid, sid)
        val priced = catalog.price(tx, rid, lines.map { LineRequest(it.itemId, it.quantity, it.optionIds) })
        if (!priced.ok) throw problemOf(priced.problems)
        val now = Rfc3339.truncate(clock.instant())
        val order = NewOrder(OrderId(ids.next()), sid, now, placer.staffId, snapshot(priced.lines, lines))
        // The bill row is made with the session's first order; orders of one session queue on it.
        bills.createIfNone(tx, rid, sid, now)
        bills.lock(tx, rid, sid)
        bills.change(tx, rid, sid, order.total, 1)
        orders.insert(tx, rid, order)
        // Counted once it's saved: a rolled-back order (or a replayed key's answer) isn't an order placed.
        tx.afterCommit { meters.orderPlaced(byStaff = placer.staffId != null) }
        access.touch(tx, rid, sid, now)
        val subject = EventSubject(OrderEvents.ORDER_AGGREGATE, order.id.value, 0)
        events.append(tx, rid, OrderEvents.ORDER_PLACED, subject, EventAudience.STAFF_AND_SESSION, sid)
        if (placer.staffId != null) {
            val details =
                mapOf(
                    "session_id" to JsonPrimitive(sid.toString()),
                    "total" to JsonPrimitive(order.total.toString()),
                    "line_count" to JsonPrimitive(order.lines.size),
                )
            audit.record(
                tx,
                AuditEvent(rid, placer.actor, "order.placed_by_staff", OrderTargets.ORDER, order.id.value, JsonObject(details), request),
            )
        }
        return Order(
            id = order.id,
            sessionId = sid,
            tableId = session.tableId.value,
            tableNumber = session.tableNumber,
            status = OrderStatus.PLACED,
            placedAt = now,
            placedByStaff = placer.staffId,
            total = order.total,
            statusChangedAt = now,
            reason = null,
            version = 0,
            lines = order.lines,
        )
    }

    /** The lines as the order keeps them (FR-007): names and prices now, whatever the menu does later. */
    private fun snapshot(
        priced: List<PricedLine>,
        inputs: List<LineInput>,
    ): List<OrderLine> =
        priced.mapIndexed { index, line ->
            OrderLine(
                id = OrderLineId(ids.next()),
                lineNo = index + 1,
                itemId = line.itemId,
                nameVi = line.nameVi,
                nameEn = line.nameEn,
                options = line.options.map { LineOption(it.optionId, it.groupNameVi, it.groupNameEn, it.nameVi, it.nameEn, it.priceDelta) },
                unitPrice = line.price,
                unitTotal = line.unitTotal,
                quantity = line.quantity,
                lineTotal = line.unitTotal * line.quantity,
                note = inputs[index].note,
                status = LineStatus.ACTIVE,
                reason = null,
            )
        }

    /**
     * The menu's problems as the client sees them (orders §3.1 step 4). A line the client got wrong
     * is `400`, one entry per problem; only when every problem is a hidden or sold-out item is it
     * `409 item_unavailable`, listing them, so the app can mark them.
     */
    private fun problemOf(problems: List<LineProblem>): DomainError {
        val invalid = problems.filter { it.code != LineProblemCode.ITEM_UNAVAILABLE }
        if (invalid.isEmpty()) return DomainError.ItemUnavailable(problems.map { it.itemId }.distinct())
        return DomainError.ValidationFailed(
            invalid.map {
                val field = if (it.code == LineProblemCode.UNKNOWN_ITEM) "/lines/${it.line}/item_id" else "/lines/${it.line}/option_ids"
                FieldError(field, it.code.value)
            },
        )
    }
}
