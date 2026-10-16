package io.github.phongmanh.restaurant.orders.application

import io.github.phongmanh.restaurant.core.application.AuditEvent
import io.github.phongmanh.restaurant.core.application.AuditLog
import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.FieldError
import io.github.phongmanh.restaurant.core.domain.IdGenerator
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.Rfc3339
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.http.Page
import io.github.phongmanh.restaurant.core.http.PageRequest
import io.github.phongmanh.restaurant.core.http.pageOf
import io.github.phongmanh.restaurant.orders.domain.AdjustmentReason
import io.github.phongmanh.restaurant.orders.domain.Bill
import io.github.phongmanh.restaurant.orders.domain.BillAdjustment
import io.github.phongmanh.restaurant.orders.domain.BillLine
import io.github.phongmanh.restaurant.orders.domain.BillStatus
import io.github.phongmanh.restaurant.orders.domain.ClosedBill
import io.github.phongmanh.restaurant.orders.domain.OrderStatus
import io.github.phongmanh.restaurant.orders.domain.PaymentMethod
import io.github.phongmanh.restaurant.orders.domain.Reason
import io.github.phongmanh.restaurant.orders.domain.ReasonCode
import io.github.phongmanh.restaurant.orders.persistence.BillClosing
import io.github.phongmanh.restaurant.orders.persistence.BillCursor
import io.github.phongmanh.restaurant.orders.persistence.BillRepository
import io.github.phongmanh.restaurant.orders.persistence.OrderRepository
import io.github.phongmanh.restaurant.orders.persistence.Payment
import io.github.phongmanh.restaurant.orders.persistence.StatusChange
import io.github.phongmanh.restaurant.realtime.application.EventAudience
import io.github.phongmanh.restaurant.realtime.application.EventLog
import io.github.phongmanh.restaurant.realtime.application.EventSubject
import io.github.phongmanh.restaurant.reports.application.ClosedBillSale
import io.github.phongmanh.restaurant.reports.application.ItemSale
import io.github.phongmanh.restaurant.reports.application.SalesLedger
import io.github.phongmanh.restaurant.tables.application.SessionAccess
import io.github.phongmanh.restaurant.tables.domain.CloseReason
import io.github.phongmanh.restaurant.tables.domain.SessionStatus
import io.github.phongmanh.restaurant.tables.domain.TableSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * What a close asks for (orders §7.1): the bill version the waiter read (`If-Match`), the payment
 * unless the bill is 0, and whether to cancel unfinished orders.
 */
data class CloseRequest(
    val version: Int,
    val payment: Payment?,
    val force: Boolean,
)

/** A correction to a closed bill (orders §7.2), checked for its form. */
data class AdjustmentInput(
    val amount: Long,
    val paymentMethod: PaymentMethod,
    val reason: AdjustmentReason,
    val note: String?,
)

/**
 * The bill (orders §4.1, §7): reading it, closing the session with the payment recorded at the
 * counter (FR-013), correcting a closed bill (TBD-7), and the bills closed on a day.
 */
class BillService(
    private val transactor: Transactor,
    private val bills: BillRepository,
    private val orders: OrderRepository,
    private val access: SessionAccess,
    private val ledger: SalesLedger,
    private val days: BusinessDay,
    private val events: EventLog,
    private val audit: AuditLog,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** The guest's running bill; their session is open, or the guest authentication would have refused. */
    suspend fun guestBill(guest: Caller.Guest): Bill =
        transactor.inTransaction(guest.restaurantId) {
            bill(this, guest.restaurantId, sessionOf(this, guest.restaurantId, guest.sessionId))
        }

    /** Any staff role; with `ETag`, which the close needs (orders §4). */
    suspend fun staffBill(
        caller: Caller.Staff,
        sessionId: TableSessionId,
    ): Bill =
        transactor.inTransaction(caller.restaurantId) { bill(this, caller.restaurantId, sessionOf(this, caller.restaurantId, sessionId)) }

    /**
     * Closes the session (orders §7.1), in the transaction of the `Idempotency-Key` helper: a
     * replay with the same key returns the stored answer, FR-013's "closing twice → same result".
     *
     * Locks: session (`FOR UPDATE`, so any order being placed finishes first) → bill → unfinished
     * orders → daily sales rows → event counter → audit chain head. The ledger is written before
     * the events, and so before the session's own `TableSessionClosed`, to keep that order.
     */
    fun close(
        tx: TxScope,
        staff: BillStaff,
        sessionId: TableSessionId,
        close: CloseRequest,
        request: RequestMeta,
    ): Bill {
        val rid = staff.restaurantId
        val session = access.lockForClose(tx, rid, sessionId)
        val now = Rfc3339.truncate(clock.instant())
        bills.createIfNone(tx, rid, sessionId, now)
        val bill = bills.lock(tx, rid, sessionId)!!
        if (bill.version != close.version) throw DomainError.PreconditionFailed()
        val unfinished = orders.lockUnfinished(tx, rid, sessionId)
        if (unfinished.isNotEmpty() && !close.force) throw DomainError.OrdersNotFinished(unfinished.map { it.id.value })
        val forced = Reason(ReasonCode.CLOSED_WITH_SESSION, null)
        unfinished.forEach { order ->
            orders.changeStatus(tx, rid, order.id, StatusChange(OrderStatus.CANCELLED, forced, now, staff.staffId))
            bills.change(tx, rid, sessionId, -order.total, -1)
        }
        val total = bill.total - unfinished.sumOf { it.total }
        val lines = orders.billLines(tx, rid, sessionId)
        // Kept twice on purpose (orders §6); a difference is a bug, never the waiter's mistake.
        check(lines.sumOf { it.line.lineTotal } == total) { "bill total differs from its lines" }
        requirePayment(total, close.payment)
        val businessDate = days.of(tx, rid, now)
        bills.close(tx, rid, sessionId, BillClosing(close.payment, now, staff.staffId, businessDate))
        if (total > 0) {
            val sale =
                ClosedBillSale(
                    businessDate = businessDate,
                    tableId = session.tableId,
                    tableNumber = session.tableNumber,
                    paymentMethod = close.payment!!.method.value,
                    total = total,
                    orderCount = bill.orderCount - unfinished.size,
                    items = itemsOf(lines),
                )
            ledger.recordClosedBill(tx, rid, sale)
        }
        unfinished.forEach { order ->
            val subject = EventSubject(OrderEvents.ORDER_AGGREGATE, order.id.value, order.version + 1)
            events.append(tx, rid, OrderEvents.ORDER_STATUS_CHANGED, subject, EventAudience.STAFF_AND_SESSION, sessionId)
        }
        val closed = bills.find(tx, rid, sessionId)!!
        events.append(
            tx,
            rid,
            OrderEvents.BILL_CLOSED,
            EventSubject(OrderEvents.BILL_AGGREGATE, sessionId.value, closed.version),
            EventAudience.STAFF,
        )
        access.close(tx, rid, sessionId, if (total > 0) CloseReason.PAID else CloseReason.CLOSED_BY_STAFF, now, staff.staffId)
        val details =
            buildMap {
                put("total", JsonPrimitive(total.toString()))
                close.payment?.let {
                    put("method", JsonPrimitive(it.method.value))
                    put("amount_received", JsonPrimitive(it.amountReceived.toString()))
                }
                put("forced_cancellations", JsonPrimitive(unfinished.size))
            }
        audit.record(tx, AuditEvent(rid, staff.actor, "bill.closed", OrderTargets.BILL, sessionId.value, JsonObject(details), request))
        return bill(tx, rid, sessionOf(tx, rid, sessionId))
    }

    /**
     * A manager's correction to a closed bill (orders §7.2): a new entry, never an edit, counted on
     * the local day it's made. `If-Match` on the bill makes a retry harmless: the first call moves
     * the version on, so a repeat gets `412` instead of correcting twice.
     */
    suspend fun adjust(
        manager: BillManager,
        sessionId: TableSessionId,
        version: Int,
        input: AdjustmentInput,
        request: RequestMeta,
    ): Bill =
        transactor.inTransaction(manager.restaurantId) {
            val rid = manager.restaurantId
            val session = sessionOf(this, rid, sessionId)
            val bill = bills.lock(this, rid, sessionId)
            if (bill == null || bill.status != BillStatus.CLOSED) throw DomainError.BillNotClosed()
            if (bill.version != version) throw DomainError.PreconditionFailed()
            if (bill.total + bill.adjustmentsTotal + input.amount < 0) {
                throw DomainError.ValidationFailed(listOf(FieldError("/amount", "below_zero", "the corrected total can't go below 0")))
            }
            val now = Rfc3339.truncate(clock.instant())
            val businessDate = days.of(this, rid, now)
            val adjustment =
                BillAdjustment(ids.next(), input.amount, input.paymentMethod, input.reason, input.note, now, manager.staffId, businessDate)
            bills.adjust(this, rid, sessionId, adjustment)
            ledger.recordCorrection(this, rid, businessDate, input.paymentMethod.value, input.amount)
            val subject = EventSubject(OrderEvents.BILL_AGGREGATE, sessionId.value, bill.version + 1)
            events.append(this, rid, OrderEvents.BILL_ADJUSTED, subject, EventAudience.STAFF)
            val details =
                mapOf(
                    "amount" to JsonPrimitive(input.amount.toString()),
                    "method" to JsonPrimitive(input.paymentMethod.value),
                    "reason_code" to JsonPrimitive(input.reason.value),
                )
            audit.record(
                this,
                AuditEvent(rid, manager.actor, "bill.adjusted", OrderTargets.BILL, sessionId.value, JsonObject(details), request),
            )
            bill(this, rid, session)
        }

    /** Bills closed on the local day [date], newest first: to find one to correct or show again (orders §4). */
    suspend fun closedOn(
        staff: BillStaff,
        date: LocalDate,
        page: PageRequest,
    ): Page<ClosedBill> =
        transactor.inTransaction(staff.restaurantId) {
            val rid = staff.restaurantId
            val after =
                page.after?.let { key ->
                    runCatching { BillCursor(Instant.parse(key[0]), UUID.fromString(key[1])) }.getOrNull()
                        ?: throw DomainError.ValidationFailed(
                            listOf(FieldError("query.cursor", "invalid", "use next_cursor from the previous page")),
                        )
                }
            val rows = bills.closedOn(this, rid, date, after, page.limit + 1)
            val sessions = access.find(this, rid, rows.map { it.sessionId }.toSet())
            val closed =
                rows.map {
                    val session = sessions.getValue(it.sessionId)
                    ClosedBill(
                        it.sessionId,
                        session.tableId.value,
                        session.tableNumber,
                        it.total,
                        it.adjustmentsTotal,
                        it.paymentMethod,
                        it.closedAt!!,
                    )
                }
            pageOf(closed, page) { listOf(it.closedAt.toString(), it.sessionId.toString()) }
        }

    /**
     * The bill as it is now. A session without orders has no bill row: it reads as an empty bill
     * with version 0, open or closed as its session is (orders §4.1).
     */
    fun bill(
        tx: TxScope,
        restaurantId: RestaurantId,
        session: TableSession,
    ): Bill {
        val row = bills.find(tx, restaurantId, session.id)
        if (row == null) {
            val status = if (session.status == SessionStatus.OPEN) BillStatus.OPEN else BillStatus.CLOSED
            return Bill(
                session.id,
                session.tableId.value,
                session.tableNumber,
                status,
                0,
                0,
                0,
                emptyList(),
                session.closedAt,
                null,
                null,
                null,
                emptyList(),
                0,
            )
        }
        val adjustments = if (row.status == BillStatus.CLOSED) bills.adjustments(tx, restaurantId, session.id) else emptyList()
        return Bill(
            sessionId = session.id,
            tableId = session.tableId.value,
            tableNumber = session.tableNumber,
            status = row.status,
            version = row.version,
            orderCount = row.orderCount,
            total = row.total,
            lines = orders.billLines(tx, restaurantId, session.id),
            closedAt = row.closedAt,
            businessDate = row.businessDate,
            paymentMethod = row.paymentMethod,
            amountReceived = row.amountReceived,
            adjustments = adjustments,
            adjustmentsTotal = row.adjustmentsTotal,
        )
    }

    private fun sessionOf(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
    ): TableSession = access.find(tx, restaurantId, listOf(sessionId))[sessionId] ?: throw DomainError.NotFound()

    /**
     * The payment rule of FR-013 (§15 Q5): a bill above 0 needs a payment that covers it; a bill of 0
     * (nothing ordered, or all cancelled) closes without one.
     */
    private fun requirePayment(
        total: Long,
        payment: Payment?,
    ) {
        val error =
            when {
                total > 0 && payment == null -> FieldError("/payment", "required", "a bill above 0 needs its payment")
                total > 0 && payment!!.amountReceived < total -> FieldError("/payment/amount_received", "less_than_total")
                total == 0L && payment != null -> FieldError("/payment", "not_allowed", "a bill of 0 closes without a payment")
                else -> null
            }
        error?.let { throw DomainError.ValidationFailed(listOf(it)) }
    }

    /** The bill's lines summed per item, for the daily item totals (orders §7.3); names from the latest line. */
    private fun itemsOf(lines: List<BillLine>): List<ItemSale> =
        lines
            .groupBy { it.line.itemId }
            .map { (itemId, of) ->
                val latest = of.last().line
                ItemSale(itemId, latest.nameVi, latest.nameEn, of.sumOf { it.line.quantity }, of.sumOf { it.line.lineTotal })
            }
}
