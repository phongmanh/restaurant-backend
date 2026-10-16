package io.github.phongmanh.restaurant.orders.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.instant
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.core.persistence.localDate
import io.github.phongmanh.restaurant.core.persistence.uuid
import io.github.phongmanh.restaurant.orders.domain.AdjustmentReason
import io.github.phongmanh.restaurant.orders.domain.BillAdjustment
import io.github.phongmanh.restaurant.orders.domain.BillRow
import io.github.phongmanh.restaurant.orders.domain.BillStatus
import io.github.phongmanh.restaurant.orders.domain.PaymentMethod
import io.github.phongmanh.restaurant.tables.application.BillSummary
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The payment recorded at a close (FR-013). */
data class Payment(
    val method: PaymentMethod,
    val amountReceived: Long,
)

/** How a bill closes (FR-013): the payment, if any; when and by whom (`null` = the system); the local day. */
data class BillClosing(
    val payment: Payment?,
    val at: Instant,
    val by: StaffId?,
    val businessDate: LocalDate,
)

/** Where a page of closed bills starts: after the bill closed at [closedAt] for [sessionId], newest first. */
data class BillCursor(
    val closedAt: Instant,
    val sessionId: UUID,
)

/** `bills` and `bill_adjustments` (orders §6). Every method takes the restaurant and filters by it. */
class BillRepository {
    /** Creates the session's open bill unless it has one (orders §3.1 step 5, §7.1 step 3). */
    fun createIfNone(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
        now: Instant,
    ) {
        connection(tx)
            .prepareStatement(
                "INSERT INTO bills (session_id, restaurant_id, status, created_at) VALUES (?, ?, 'open', ?) " +
                    "ON CONFLICT (session_id) DO NOTHING",
            ).use { it.bind(sessionId.value, restaurantId.value, now).executeUpdate() }
    }

    fun find(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
    ): BillRow? =
        connection(tx).prepareStatement("$SELECT WHERE restaurant_id = ? AND session_id = ?").use {
            it.bind(restaurantId.value, sessionId.value).firstOrNull(::bill)
        }

    /** The bill with its row locked until the transaction ends (`FOR UPDATE`). */
    fun lock(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
    ): BillRow? =
        connection(tx).prepareStatement("$SELECT WHERE restaurant_id = ? AND session_id = ? FOR UPDATE").use {
            it.bind(restaurantId.value, sessionId.value).firstOrNull(::bill)
        }

    /**
     * Adds [amount] to the open bill's total and [orders] to its order count (either may be
     * negative: a cancelled order or a rejected line leaves it), and increases its version.
     */
    fun change(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
        amount: Long,
        orders: Int,
    ) {
        val changed =
            connection(tx)
                .prepareStatement(
                    "UPDATE bills SET total = total + ?, order_count = order_count + ?, version = version + 1 " +
                        "WHERE restaurant_id = ? AND session_id = ? AND status = 'open'",
                ).use { it.bind(amount, orders, restaurantId.value, sessionId.value).executeUpdate() }
        check(changed == 1) { "the bill must be open and locked" }
    }

    /** Closes the open bill (FR-013). */
    fun close(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
        closing: BillClosing,
    ) {
        val changed =
            connection(tx)
                .prepareStatement(
                    "UPDATE bills SET status = 'closed', payment_method = ?::text, amount_received = ?::bigint, closed_at = ?, " +
                        "closed_by = ?::uuid, business_date = ?, version = version + 1 " +
                        "WHERE restaurant_id = ? AND session_id = ? AND status = 'open'",
                ).use {
                    it
                        .bind(
                            closing.payment?.method?.value,
                            closing.payment?.amountReceived,
                            closing.at,
                            closing.by?.value,
                            closing.businessDate,
                            restaurantId.value,
                            sessionId.value,
                        ).executeUpdate()
                }
        check(changed == 1) { "the bill must be open and locked" }
    }

    /** Adds a correction to the closed bill (orders §7.2): the entry, its running sum, and a new version. */
    fun adjust(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
        adjustment: BillAdjustment,
    ) {
        val c = connection(tx)
        c
            .prepareStatement(
                "INSERT INTO bill_adjustments (id, restaurant_id, session_id, amount, payment_method, reason_code, note, created_at, " +
                    "created_by, business_date) VALUES (?, ?, ?, ?, ?, ?, ?::text, ?, ?, ?)",
            ).use {
                it
                    .bind(
                        adjustment.id,
                        restaurantId.value,
                        sessionId.value,
                        adjustment.amount,
                        adjustment.paymentMethod.value,
                        adjustment.reason.value,
                        adjustment.note,
                        adjustment.createdAt,
                        adjustment.createdBy.value,
                        adjustment.businessDate,
                    ).executeUpdate()
            }
        c
            .prepareStatement(
                "UPDATE bills SET adjustments_total = adjustments_total + ?, version = version + 1 " +
                    "WHERE restaurant_id = ? AND session_id = ? AND status = 'closed'",
            ).use { it.bind(adjustment.amount, restaurantId.value, sessionId.value).executeUpdate() }
    }

    /** The bill's corrections, oldest first. */
    fun adjustments(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
    ): List<BillAdjustment> =
        connection(tx)
            .prepareStatement(
                "SELECT id, amount, payment_method, reason_code, note, created_at, created_by, business_date FROM bill_adjustments " +
                    "WHERE restaurant_id = ? AND session_id = ? ORDER BY created_at, id",
            ).use {
                it.bind(restaurantId.value, sessionId.value).list { rs ->
                    BillAdjustment(
                        id = rs.uuid("id")!!,
                        amount = rs.getLong("amount"),
                        paymentMethod = PaymentMethod.of(rs.getString("payment_method")),
                        reason = AdjustmentReason.of(rs.getString("reason_code")),
                        note = rs.getString("note"),
                        createdAt = rs.instant("created_at")!!,
                        createdBy = StaffId(rs.uuid("created_by")!!),
                        businessDate = rs.localDate("business_date")!!,
                    )
                }
            }

    /** The `SessionSummaries` port's one query (tables §6): order count and total per session. */
    fun summaries(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessions: List<TableSessionId>,
    ): Map<TableSessionId, BillSummary> {
        if (sessions.isEmpty()) return emptyMap()
        return connection(tx)
            .prepareStatement("SELECT session_id, order_count, total FROM bills WHERE restaurant_id = ? AND session_id = ANY(?::uuid[])")
            .use {
                it.bind(restaurantId.value, sessions.map(TableSessionId::toString)).list { rs ->
                    TableSessionId(rs.uuid("session_id")!!) to BillSummary(rs.getInt("order_count"), rs.getLong("total"))
                }
            }.toMap()
    }

    /** Bills closed on [date], newest first (orders §4). Fetches `limit` rows; pass one more to see a next page. */
    fun closedOn(
        tx: TxScope,
        restaurantId: RestaurantId,
        date: LocalDate,
        after: BillCursor?,
        limit: Int,
    ): List<BillRow> =
        connection(tx)
            .prepareStatement(
                "$SELECT WHERE restaurant_id = ? AND status = 'closed' AND business_date = ? " +
                    "AND (?::timestamptz IS NULL OR (closed_at, session_id) < (?::timestamptz, ?::uuid)) " +
                    "ORDER BY closed_at DESC, session_id DESC LIMIT ?",
            ).use {
                it.bind(restaurantId.value, date, after?.closedAt, after?.closedAt, after?.sessionId, limit).list(::bill)
            }

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)

    private companion object {
        const val SELECT =
            "SELECT session_id, status, order_count, total, adjustments_total, payment_method, amount_received, closed_at, " +
                "business_date, version FROM bills"

        fun bill(rs: ResultSet) =
            BillRow(
                sessionId = TableSessionId(rs.uuid("session_id")!!),
                status = BillStatus.of(rs.getString("status")),
                orderCount = rs.getInt("order_count"),
                total = rs.getLong("total"),
                adjustmentsTotal = rs.getLong("adjustments_total"),
                paymentMethod = rs.getString("payment_method")?.let(PaymentMethod::of),
                amountReceived = rs.getLong("amount_received").takeUnless { rs.wasNull() },
                closedAt = rs.instant("closed_at"),
                businessDate = rs.localDate("business_date"),
                version = rs.getInt("version"),
            )
    }
}
