package io.github.phongmanh.restaurant.orders.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.http.ApiJson
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.instant
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.core.persistence.uuid
import io.github.phongmanh.restaurant.orders.domain.BillLine
import io.github.phongmanh.restaurant.orders.domain.LineOption
import io.github.phongmanh.restaurant.orders.domain.LineStatus
import io.github.phongmanh.restaurant.orders.domain.OrderId
import io.github.phongmanh.restaurant.orders.domain.OrderLine
import io.github.phongmanh.restaurant.orders.domain.OrderLineId
import io.github.phongmanh.restaurant.orders.domain.OrderStatus
import io.github.phongmanh.restaurant.orders.domain.Reason
import io.github.phongmanh.restaurant.orders.domain.ReasonCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** An `orders` row, without its lines or table: the service puts those together (orders §4). */
data class OrderRow(
    val id: OrderId,
    val sessionId: TableSessionId,
    val status: OrderStatus,
    val placedAt: Instant,
    val placedByStaff: StaffId?,
    val total: Long,
    val statusChangedAt: Instant,
    val reason: Reason?,
    val version: Int,
)

/** A new order and its priced lines (orders §3.1 step 6). */
data class NewOrder(
    val id: OrderId,
    val sessionId: TableSessionId,
    val placedAt: Instant,
    val placedByStaff: StaffId?,
    val lines: List<OrderLine>,
) {
    val total: Long get() = lines.sumOf { it.lineTotal }
}

/** Where a page of orders starts: after the order placed at [placedAt] with [id], in the list's order. */
data class OrderCursor(
    val placedAt: Instant,
    val id: UUID,
)

/** A page to fetch: after [after] (`null` = from the start), at most [limit] rows. */
data class OrderPage(
    val after: OrderCursor?,
    val limit: Int,
)

/** An order's step (orders §5): the new status, its reason, when, and by whom (`null` = a guest or the system). */
data class StatusChange(
    val to: OrderStatus,
    val reason: Reason?,
    val at: Instant,
    val by: StaffId?,
)

/** A line's rejection (orders §5.3): the reason, when, and by whom. */
data class LineRejection(
    val reason: Reason,
    val at: Instant,
    val by: StaffId,
)

/** `orders` and `order_lines` (orders §6). Every method takes the restaurant and filters by it. */
class OrderRepository {
    fun insert(
        tx: TxScope,
        restaurantId: RestaurantId,
        order: NewOrder,
    ) {
        val c = connection(tx)
        c
            .prepareStatement(
                "INSERT INTO orders (id, restaurant_id, session_id, status, placed_at, placed_by_staff, total, status_changed_at) " +
                    "VALUES (?, ?, ?, 'placed', ?, ?::uuid, ?, ?)",
            ).use {
                it
                    .bind(
                        order.id.value,
                        restaurantId.value,
                        order.sessionId.value,
                        order.placedAt,
                        order.placedByStaff?.value,
                        order.total,
                        order.placedAt,
                    ).executeUpdate()
            }
        c
            .prepareStatement(
                "INSERT INTO order_lines (id, restaurant_id, order_id, line_no, item_id, name_vi, name_en, options, unit_price, " +
                    "unit_total, quantity, line_total, note) VALUES (?, ?, ?, ?, ?, ?, ?::text, ?::jsonb, ?, ?, ?, ?, ?::text)",
            ).use { statement ->
                order.lines.forEach { line ->
                    statement
                        .bind(
                            line.id.value,
                            restaurantId.value,
                            order.id.value,
                            line.lineNo,
                            line.itemId,
                            line.nameVi,
                            line.nameEn,
                            ApiJson.encodeToString(OPTIONS, line.options.map(StoredOption::of)),
                            line.unitPrice,
                            line.unitTotal,
                            line.quantity,
                            line.lineTotal,
                            line.note,
                        ).addBatch()
                }
                statement.executeBatch()
            }
    }

    /** The orders of [ids] that exist, in one query (one order, or the batch of realtime's catch-up, §6.3). */
    fun find(
        tx: TxScope,
        restaurantId: RestaurantId,
        ids: List<OrderId>,
    ): List<OrderRow> =
        connection(tx).prepareStatement("$SELECT WHERE restaurant_id = ? AND id = ANY(?::uuid[]) ORDER BY placed_at, id").use {
            it.bind(restaurantId.value, ids.map { id -> id.toString() }).list(::order)
        }

    /** The order with its row locked until the transaction ends (`FOR UPDATE`). */
    fun lock(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: OrderId,
    ): OrderRow? =
        connection(tx).prepareStatement("$SELECT WHERE restaurant_id = ? AND id = ? FOR UPDATE").use {
            it.bind(restaurantId.value, id.value).firstOrNull(::order)
        }

    /** The session's orders still being made, locked, by ID so two closes lock them in the same order. */
    fun lockUnfinished(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
    ): List<OrderRow> =
        connection(tx)
            .prepareStatement(
                "$SELECT WHERE restaurant_id = ? AND session_id = ? AND status IN ('placed', 'accepted', 'preparing', 'ready') " +
                    "ORDER BY id FOR UPDATE",
            ).use { it.bind(restaurantId.value, sessionId.value).list(::order) }

    /** A guest's orders: the session's, newest first (orders §4). Ask for one row more than a page to see a next one. */
    fun ofSession(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
        page: OrderPage,
    ): List<OrderRow> {
        val after = page.after
        return connection(tx)
            .prepareStatement(
                "$SELECT WHERE restaurant_id = ? AND session_id = ? " +
                    "AND (?::timestamptz IS NULL OR (placed_at, id) < (?::timestamptz, ?::uuid)) " +
                    "ORDER BY placed_at DESC, id DESC LIMIT ?",
            ).use {
                it
                    .bind(restaurantId.value, sessionId.value, after?.placedAt, after?.placedAt, after?.id, page.limit)
                    .list(::order)
            }
    }

    /** The staff feed (orders §4): orders of [sessions] in [statuses], oldest first, as the kitchen works. */
    fun feed(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessions: List<TableSessionId>,
        statuses: Set<OrderStatus>,
        page: OrderPage,
    ): List<OrderRow> {
        val after = page.after
        return connection(tx)
            .prepareStatement(
                "$SELECT WHERE restaurant_id = ? AND session_id = ANY(?::uuid[]) AND status = ANY(?::text[]) " +
                    "AND (?::timestamptz IS NULL OR (placed_at, id) > (?::timestamptz, ?::uuid)) " +
                    "ORDER BY placed_at, id LIMIT ?",
            ).use {
                it
                    .bind(
                        restaurantId.value,
                        sessions.map { s -> s.toString() },
                        statuses.map(OrderStatus::value),
                        after?.placedAt,
                        after?.placedAt,
                        after?.id,
                        page.limit,
                    ).list(::order)
            }
    }

    /** The lines of [orders], each order's in line order. */
    fun lines(
        tx: TxScope,
        restaurantId: RestaurantId,
        orders: List<OrderId>,
    ): Map<OrderId, List<OrderLine>> {
        if (orders.isEmpty()) return emptyMap()
        return connection(tx)
            .prepareStatement("$SELECT_LINE WHERE restaurant_id = ? AND order_id = ANY(?::uuid[]) ORDER BY order_id, line_no")
            .use { it.bind(restaurantId.value, orders.map(OrderId::toString)).list { rs -> OrderId(rs.uuid("order_id")!!) to line(rs) } }
            .groupBy({ it.first }, { it.second })
    }

    /**
     * The bill's lines (orders §4.1): active lines of the session's orders that aren't cancelled or
     * rejected, in the order they were placed.
     */
    fun billLines(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
    ): List<BillLine> =
        connection(tx)
            .prepareStatement(
                "SELECT l.order_id, ${LINE_COLUMNS.split(", ").joinToString(", ") { "l.$it" }} FROM order_lines l " +
                    "JOIN orders o ON o.restaurant_id = l.restaurant_id AND o.id = l.order_id " +
                    "WHERE o.restaurant_id = ? AND o.session_id = ? AND o.status NOT IN ('rejected', 'cancelled') " +
                    "AND l.status = 'active' ORDER BY o.placed_at, o.id, l.line_no",
            ).use { it.bind(restaurantId.value, sessionId.value).list { rs -> BillLine(OrderId(rs.uuid("order_id")!!), line(rs)) } }

    /** Moves the order to its new status and increases its version. */
    fun changeStatus(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: OrderId,
        change: StatusChange,
    ) {
        val reason = change.reason
        connection(tx)
            .prepareStatement(
                "UPDATE orders SET status = ?, reason_code = ?::text, reason_note = ?::text, status_changed_at = ?, " +
                    "status_changed_by = ?::uuid, version = version + 1 WHERE restaurant_id = ? AND id = ?",
            ).use {
                it
                    .bind(
                        change.to.value,
                        reason?.code?.value,
                        reason?.note,
                        change.at,
                        change.by?.value,
                        restaurantId.value,
                        id.value,
                    ).executeUpdate()
            }
    }

    /** Rejects one line (orders §5.3): the line leaves the order's total, and the order's version goes up. */
    fun rejectLine(
        tx: TxScope,
        restaurantId: RestaurantId,
        orderId: OrderId,
        line: OrderLine,
        rejection: LineRejection,
    ) {
        val reason = rejection.reason
        val c = connection(tx)
        c
            .prepareStatement(
                "UPDATE order_lines SET status = 'rejected', reason_code = ?, reason_note = ?::text, rejected_at = ?, rejected_by = ? " +
                    "WHERE restaurant_id = ? AND id = ? AND status = 'active'",
            ).use {
                it
                    .bind(
                        reason.code.value,
                        reason.note,
                        rejection.at,
                        rejection.by.value,
                        restaurantId.value,
                        line.id.value,
                    ).executeUpdate()
            }
        c
            .prepareStatement("UPDATE orders SET total = total - ?, version = version + 1 WHERE restaurant_id = ? AND id = ?")
            .use { it.bind(line.lineTotal, restaurantId.value, orderId.value).executeUpdate() }
    }

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)

    /** An option as `order_lines.options` stores it (orders §6). */
    @Serializable
    private data class StoredOption(
        @SerialName("option_id") val optionId: String,
        @SerialName("group_name_vi") val groupNameVi: String,
        @SerialName("group_name_en") val groupNameEn: String? = null,
        @SerialName("name_vi") val nameVi: String,
        @SerialName("name_en") val nameEn: String? = null,
        @SerialName("price_delta") val priceDelta: Long,
    ) {
        fun toOption() = LineOption(UUID.fromString(optionId), groupNameVi, groupNameEn, nameVi, nameEn, priceDelta)

        companion object {
            fun of(o: LineOption) = StoredOption(o.optionId.toString(), o.groupNameVi, o.groupNameEn, o.nameVi, o.nameEn, o.priceDelta)
        }
    }

    private companion object {
        val OPTIONS = ListSerializer(StoredOption.serializer())

        const val SELECT =
            "SELECT id, session_id, status, placed_at, placed_by_staff, total, status_changed_at, reason_code, reason_note, version " +
                "FROM orders"

        const val LINE_COLUMNS =
            "id, line_no, item_id, name_vi, name_en, options, unit_price, unit_total, quantity, line_total, note, status, " +
                "reason_code, reason_note"

        const val SELECT_LINE = "SELECT order_id, $LINE_COLUMNS FROM order_lines"

        fun order(rs: ResultSet) =
            OrderRow(
                id = OrderId(rs.uuid("id")!!),
                sessionId = TableSessionId(rs.uuid("session_id")!!),
                status = OrderStatus.of(rs.getString("status")),
                placedAt = rs.instant("placed_at")!!,
                placedByStaff = rs.uuid("placed_by_staff")?.let(::StaffId),
                total = rs.getLong("total"),
                statusChangedAt = rs.instant("status_changed_at")!!,
                reason = reason(rs),
                version = rs.getInt("version"),
            )

        fun line(rs: ResultSet) =
            OrderLine(
                id = OrderLineId(rs.uuid("id")!!),
                lineNo = rs.getInt("line_no"),
                itemId = rs.uuid("item_id")!!,
                nameVi = rs.getString("name_vi"),
                nameEn = rs.getString("name_en"),
                options = ApiJson.decodeFromString(OPTIONS, rs.getString("options")).map(StoredOption::toOption),
                unitPrice = rs.getLong("unit_price"),
                unitTotal = rs.getLong("unit_total"),
                quantity = rs.getInt("quantity"),
                lineTotal = rs.getLong("line_total"),
                note = rs.getString("note"),
                status = LineStatus.of(rs.getString("status")),
                reason = reason(rs),
            )

        fun reason(rs: ResultSet): Reason? = rs.getString("reason_code")?.let { Reason(ReasonCode.of(it), rs.getString("reason_note")) }
    }
}

/**
 * The retention job's reads and writes of `orders` and `order_lines` (orders §6): free text is
 * cleared 30 days after the bill closes. [restaurantsWithNotes] is the one platform-wide search of
 * this feature; the notes are then cleared in each restaurant's own transaction.
 */
class NoteRepository {
    /**
     * The restaurants with notes to clear: on lines or orders of bills closed before [closedBefore]
     * (orders §6). A platform transaction; the partial indexes keep it to rows that still have notes.
     */
    fun restaurantsWithNotes(
        tx: TxScope,
        closedBefore: Instant,
        limit: Int,
    ): List<RestaurantId> =
        connection(tx)
            .prepareStatement(
                "SELECT o.restaurant_id FROM order_lines l JOIN orders o ON o.restaurant_id = l.restaurant_id AND o.id = l.order_id " +
                    "JOIN bills b ON b.restaurant_id = o.restaurant_id AND b.session_id = o.session_id " +
                    "WHERE (l.note IS NOT NULL OR l.reason_note IS NOT NULL) AND b.status = 'closed' AND b.closed_at < ? " +
                    "UNION SELECT o.restaurant_id FROM orders o " +
                    "JOIN bills b ON b.restaurant_id = o.restaurant_id AND b.session_id = o.session_id " +
                    "WHERE o.reason_note IS NOT NULL AND b.status = 'closed' AND b.closed_at < ? LIMIT ?",
            ).use { it.bind(closedBefore, closedBefore, limit).list { rs -> RestaurantId(rs.uuid("restaurant_id")!!) } }

    /** Clears up to [limit] lines' and orders' notes of bills closed before [closedBefore]; returns the rows changed. */
    fun clearNotes(
        tx: TxScope,
        restaurantId: RestaurantId,
        closedBefore: Instant,
        limit: Int,
    ): Int {
        val c = connection(tx)
        val lines =
            c
                .prepareStatement(
                    "UPDATE order_lines SET note = NULL, reason_note = NULL WHERE restaurant_id = ? AND id IN (" +
                        "SELECT l.id FROM order_lines l JOIN orders o ON o.restaurant_id = l.restaurant_id AND o.id = l.order_id " +
                        "JOIN bills b ON b.restaurant_id = o.restaurant_id AND b.session_id = o.session_id " +
                        "WHERE l.restaurant_id = ? AND (l.note IS NOT NULL OR l.reason_note IS NOT NULL) " +
                        "AND b.status = 'closed' AND b.closed_at < ? LIMIT ?)",
                ).use { it.bind(restaurantId.value, restaurantId.value, closedBefore, limit).executeUpdate() }
        val orders =
            c
                .prepareStatement(
                    "UPDATE orders SET reason_note = NULL WHERE restaurant_id = ? AND id IN (" +
                        "SELECT o.id FROM orders o JOIN bills b ON b.restaurant_id = o.restaurant_id AND b.session_id = o.session_id " +
                        "WHERE o.restaurant_id = ? AND o.reason_note IS NOT NULL AND b.status = 'closed' AND b.closed_at < ? LIMIT ?)",
                ).use { it.bind(restaurantId.value, restaurantId.value, closedBefore, limit).executeUpdate() }
        return lines + orders
    }

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)
}
