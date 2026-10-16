package io.github.phongmanh.restaurant.orders.domain

import io.github.phongmanh.restaurant.core.application.StaffRole
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

@JvmInline
value class OrderId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class OrderLineId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

/**
 * An order's status (orders §2). An order moves forward one step at a time; a finished one
 * (`served`, `rejected`, `cancelled`) never changes again. Clients accept values they don't know.
 */
enum class OrderStatus {
    PLACED,
    ACCEPTED,
    PREPARING,
    READY,
    SERVED,
    REJECTED,
    CANCELLED,
    ;

    val value: String get() = name.lowercase(Locale.ROOT)

    /** On the bill: every status but `rejected` and `cancelled` (orders §4.1). */
    val live: Boolean get() = this != REJECTED && this != CANCELLED

    /** Still being made: `placed` to `ready`. A close needs none of these, or `force` (orders §7.1). */
    val unfinished: Boolean get() = this in UNFINISHED

    companion object {
        val UNFINISHED = setOf(PLACED, ACCEPTED, PREPARING, READY)

        fun of(value: String): OrderStatus = entries.first { it.value == value }

        fun parse(value: String): OrderStatus? = entries.firstOrNull { it.value == value }
    }
}

/**
 * The steps staff may make (orders §2): each target status, the one status it's made from, and the
 * roles that may make it. The role check looks only at the target, so a `403` can't reveal whether
 * the order exists or what state it's in (core-and-auth §3).
 */
object Transitions {
    private class Step(
        val from: OrderStatus,
        val roles: Set<StaffRole>,
    )

    private val kitchen = setOf(StaffRole.KITCHEN, StaffRole.MANAGER)

    private val steps =
        mapOf(
            OrderStatus.ACCEPTED to Step(OrderStatus.PLACED, kitchen),
            OrderStatus.PREPARING to Step(OrderStatus.ACCEPTED, kitchen),
            OrderStatus.READY to Step(OrderStatus.PREPARING, kitchen),
            OrderStatus.SERVED to Step(OrderStatus.READY, StaffRole.entries.toSet()),
            OrderStatus.REJECTED to Step(OrderStatus.PLACED, StaffRole.entries.toSet()),
            OrderStatus.CANCELLED to Step(OrderStatus.PLACED, setOf(StaffRole.WAITER, StaffRole.MANAGER)),
        )

    /** The statuses staff can move an order to; `placed` is only where an order starts. */
    val targets: Set<OrderStatus> get() = steps.keys

    /** The roles that may move an order to [to]. */
    fun rolesFor(to: OrderStatus): Set<StaffRole> = steps[to]?.roles.orEmpty()

    /** Whether [from] → [to] is a step: no skipping, no going back (FR-012). */
    fun allowed(
        from: OrderStatus,
        to: OrderStatus,
    ): Boolean = steps[to]?.from == from

    /** Kitchen and manager reject single lines (orders §2, §5.3). */
    val lineRejecters: Set<StaffRole> get() = kitchen
}

/** Why an order or a line was rejected or cancelled (orders §5.1, §15 Q4). Guests see only the code. */
enum class ReasonCode {
    ITEM_UNAVAILABLE,
    KITCHEN_CLOSED,
    DUPLICATE_ORDER,
    NOT_AT_TABLE,
    CUSTOMER_REQUEST,

    /** Set by a close with `force` only (orders §7.1); no one sends it. */
    CLOSED_WITH_SESSION,
    OTHER,
    ;

    val value: String get() = name.lowercase(Locale.ROOT)

    companion object {
        fun of(value: String): ReasonCode = entries.first { it.value == value }

        /** A code a staff member may send; `closed_with_session` isn't one. */
        fun input(value: String): ReasonCode? = entries.firstOrNull { it.value == value && it != CLOSED_WITH_SESSION }
    }
}

/** A reason: the code, and a staff-only note (≤ 200 characters, cleared after 30 days). */
data class Reason(
    val code: ReasonCode,
    val note: String?,
)

enum class LineStatus {
    ACTIVE,
    REJECTED,
    ;

    val value: String get() = name.lowercase(Locale.ROOT)

    companion object {
        fun of(value: String): LineStatus = entries.first { it.value == value }
    }
}

/** A chosen option as the line keeps it (FR-007): names and price delta at order time. */
data class LineOption(
    val optionId: UUID,
    val groupNameVi: String,
    val groupNameEn: String?,
    val nameVi: String,
    val nameEn: String?,
    val priceDelta: Long,
)

/** One line of an order, with its snapshot of names and prices (orders §6). */
data class OrderLine(
    val id: OrderLineId,
    val lineNo: Int,
    val itemId: UUID,
    val nameVi: String,
    val nameEn: String?,
    val options: List<LineOption>,
    val unitPrice: Long,
    val unitTotal: Long,
    val quantity: Int,
    val lineTotal: Long,
    val note: String?,
    val status: LineStatus,
    val reason: Reason?,
)

/** An order with its lines and the session's current table (orders §4). */
data class Order(
    val id: OrderId,
    val sessionId: TableSessionId,
    val tableId: UUID,
    val tableNumber: String,
    val status: OrderStatus,
    val placedAt: Instant,
    val placedByStaff: StaffId?,
    val total: Long,
    val statusChangedAt: Instant,
    val reason: Reason?,
    val version: Int,
    val lines: List<OrderLine>,
)

/** `cash`, `card` or `bank_transfer` (FR-013): recorded at the counter, no payment is taken here. */
enum class PaymentMethod {
    CASH,
    CARD,
    BANK_TRANSFER,
    ;

    val value: String get() = name.lowercase(Locale.ROOT)

    companion object {
        fun of(value: String): PaymentMethod = entries.first { it.value == value }

        fun parse(value: String): PaymentMethod? = entries.firstOrNull { it.value == value }
    }
}

/** Why a closed bill was corrected (orders §7.2). */
enum class AdjustmentReason {
    WRONG_ITEM_BILLED,
    WRONG_PAYMENT_METHOD,
    GOODWILL,
    OTHER,
    ;

    val value: String get() = name.lowercase(Locale.ROOT)

    companion object {
        fun of(value: String): AdjustmentReason = entries.first { it.value == value }

        fun parse(value: String): AdjustmentReason? = entries.firstOrNull { it.value == value }
    }
}

enum class BillStatus {
    OPEN,
    CLOSED,
    ;

    val value: String get() = name.lowercase(Locale.ROOT)

    companion object {
        fun of(value: String): BillStatus = entries.first { it.value == value }
    }
}

/** The bill row (orders §6): the session's lock and running total. */
data class BillRow(
    val sessionId: TableSessionId,
    val status: BillStatus,
    val orderCount: Int,
    val total: Long,
    val adjustmentsTotal: Long,
    val paymentMethod: PaymentMethod?,
    val amountReceived: Long?,
    val closedAt: Instant?,
    val businessDate: LocalDate?,
    val version: Int,
)

/** One line on the bill: an active line of an order that's on it (orders §4.1). */
data class BillLine(
    val orderId: OrderId,
    val line: OrderLine,
)

/** A correction to a closed bill (orders §7.2): never an edit, always a new entry. */
data class BillAdjustment(
    val id: UUID,
    val amount: Long,
    val paymentMethod: PaymentMethod,
    val reason: AdjustmentReason,
    val note: String?,
    val createdAt: Instant,
    val createdBy: StaffId,
    val businessDate: LocalDate,
)

/**
 * A bill as it's read (orders §4.1). A session without orders has no bill row: it reads as an
 * empty bill, version 0, open or closed with its session.
 */
data class Bill(
    val sessionId: TableSessionId,
    val tableId: UUID,
    val tableNumber: String,
    val status: BillStatus,
    val version: Int,
    val orderCount: Int,
    val total: Long,
    val lines: List<BillLine>,
    val closedAt: Instant?,
    val businessDate: LocalDate?,
    val paymentMethod: PaymentMethod?,
    val amountReceived: Long?,
    val adjustments: List<BillAdjustment>,
    val adjustmentsTotal: Long,
)

/** A closed bill in the list of a day's bills (`GET /v1/staff/bills`). */
data class ClosedBill(
    val sessionId: TableSessionId,
    val tableId: UUID,
    val tableNumber: String,
    val total: Long,
    val adjustmentsTotal: Long,
    val paymentMethod: PaymentMethod?,
    val closedAt: Instant,
)

/** The limits on an order (orders §3.1, §6; ASSUMPTION-4). */
object OrderLimits {
    const val MAX_LINES = 50
    const val MAX_QUANTITY = 20
    const val MAX_OPTIONS = 50
    const val MAX_NOTE = 200

    /** Free text is cleared this long after the session closes (orders §6, requirements §5). */
    const val NOTE_RETENTION_DAYS = 30L
}
