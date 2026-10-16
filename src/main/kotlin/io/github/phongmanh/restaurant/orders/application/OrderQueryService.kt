package io.github.phongmanh.restaurant.orders.application

import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.FieldError
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.http.Page
import io.github.phongmanh.restaurant.core.http.PageRequest
import io.github.phongmanh.restaurant.core.http.pageOf
import io.github.phongmanh.restaurant.orders.domain.Order
import io.github.phongmanh.restaurant.orders.domain.OrderId
import io.github.phongmanh.restaurant.orders.domain.OrderStatus
import io.github.phongmanh.restaurant.orders.persistence.OrderCursor
import io.github.phongmanh.restaurant.orders.persistence.OrderPage
import io.github.phongmanh.restaurant.orders.persistence.OrderRepository
import io.github.phongmanh.restaurant.orders.persistence.OrderRow
import io.github.phongmanh.restaurant.tables.application.SessionAccess
import java.time.Instant
import java.util.UUID

/**
 * Reading orders (orders §4): a guest reads their session's, staff read their restaurant's.
 * Another session's or restaurant's order is `404`. Each order carries the table its session is at
 * now, read through `SessionAccess` in one batch (tables §7).
 */
class OrderQueryService(
    private val transactor: Transactor,
    private val orders: OrderRepository,
    private val access: SessionAccess,
) {
    /** The guest's session's orders, newest first. */
    suspend fun guestOrders(
        guest: Caller.Guest,
        page: PageRequest,
    ): Page<Order> =
        transactor.inTransaction(guest.restaurantId) {
            val rows = orders.ofSession(this, guest.restaurantId, guest.sessionId, OrderPage(cursorOf(page), page.limit + 1))
            pageOf(assemble(this, guest.restaurantId, rows), page, ::sortKey)
        }

    suspend fun guestOrder(
        guest: Caller.Guest,
        id: OrderId,
    ): Order =
        transactor.inTransaction(guest.restaurantId) {
            order(this, guest.restaurantId, id)?.takeIf { it.sessionId == guest.sessionId }
        } ?: throw DomainError.NotFound()

    /**
     * The staff feed (FR-011's list fallback): orders of the restaurant's open sessions in
     * [statuses], oldest first, optionally of one session. Any staff role.
     */
    suspend fun staffOrders(
        caller: Caller.Staff,
        statuses: Set<OrderStatus>,
        sessionId: TableSessionId?,
        page: PageRequest,
    ): Page<Order> =
        transactor.inTransaction(caller.restaurantId) {
            val rid = caller.restaurantId
            val open = access.openSessionsFor(this, rid).filter { sessionId == null || it.id == sessionId }
            if (open.isEmpty()) return@inTransaction Page(emptyList())
            val rows = orders.feed(this, rid, open.map { it.id }, statuses, OrderPage(cursorOf(page), page.limit + 1))
            pageOf(assemble(this, rid, rows), page, ::sortKey)
        }

    /** Any staff role (orders §4). */
    suspend fun staffOrder(
        caller: Caller.Staff,
        id: OrderId,
    ): Order = transactor.inTransaction(caller.restaurantId) { order(this, caller.restaurantId, id) } ?: throw DomainError.NotFound()

    /** The order as it is now in [tx], with its lines and table. */
    fun order(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: OrderId,
    ): Order? = orders.find(tx, restaurantId, id)?.let { assemble(tx, restaurantId, listOf(it)).single() }

    /** Puts [rows] together with their lines and tables: two queries, whatever the number of orders. */
    fun assemble(
        tx: TxScope,
        restaurantId: RestaurantId,
        rows: List<OrderRow>,
    ): List<Order> {
        if (rows.isEmpty()) return emptyList()
        val lines = orders.lines(tx, restaurantId, rows.map { it.id })
        val sessions = access.find(tx, restaurantId, rows.map { it.sessionId }.toSet())
        return rows.map { row ->
            val session = sessions.getValue(row.sessionId)
            Order(
                id = row.id,
                sessionId = row.sessionId,
                tableId = session.tableId.value,
                tableNumber = session.tableNumber,
                status = row.status,
                placedAt = row.placedAt,
                placedByStaff = row.placedByStaff,
                total = row.total,
                statusChangedAt = row.statusChangedAt,
                reason = row.reason,
                version = row.version,
                lines = lines[row.id].orEmpty(),
            )
        }
    }

    private fun sortKey(order: Order) = listOf(order.placedAt.toString(), order.id.toString())

    /** The page's start; a cursor whose parts don't parse is one this service didn't make → `400`. */
    private fun cursorOf(page: PageRequest): OrderCursor? =
        page.after?.let { key ->
            runCatching { OrderCursor(Instant.parse(key[0]), UUID.fromString(key[1])) }.getOrNull()
                ?: throw DomainError.ValidationFailed(
                    listOf(FieldError("query.cursor", "invalid", "use next_cursor from the previous page")),
                )
        }
}
