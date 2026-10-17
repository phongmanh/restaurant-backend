package io.github.phongmanh.restaurant.orders.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.core.persistence.uuid
import io.github.phongmanh.restaurant.orders.domain.BillLine
import io.github.phongmanh.restaurant.orders.domain.BillRow
import io.github.phongmanh.restaurant.orders.domain.OrderId
import io.github.phongmanh.restaurant.orders.domain.PaymentMethod
import java.time.LocalDate

/**
 * The raw rows of one local day's sales, for the nightly rebuild's `SalesSource` (reports §5): the
 * bills closed with a payment, their lines, and the corrections. Rows are mapped as
 * [BillRepository] and [OrderRepository] map them. Every method takes the restaurant.
 */
class SalesRowRepository {
    /**
     * The bills closed with a payment on [date], in the order they were closed: a bill of 0 isn't
     * a sale (reports §13 Q2), and the ledger never saw it.
     */
    fun soldOn(
        tx: TxScope,
        restaurantId: RestaurantId,
        date: LocalDate,
    ): List<BillRow> =
        connection(tx)
            .prepareStatement(
                "${BillRepository.SELECT} WHERE restaurant_id = ? AND status = 'closed' AND business_date = ? AND total > 0 " +
                    "ORDER BY closed_at, session_id",
            ).use { it.bind(restaurantId.value, date).list(BillRepository::bill) }

    /** The bills' active lines of their orders that count (the close's `billLines`), by session, in one query. */
    fun billLines(
        tx: TxScope,
        restaurantId: RestaurantId,
        sessionIds: Collection<TableSessionId>,
    ): Map<TableSessionId, List<BillLine>> {
        if (sessionIds.isEmpty()) return emptyMap()
        val columns = OrderRepository.LINE_COLUMNS.split(", ").joinToString(", ") { "l.$it" }
        return connection(tx)
            .prepareStatement(
                "SELECT o.session_id, l.order_id, $columns FROM order_lines l " +
                    "JOIN orders o ON o.restaurant_id = l.restaurant_id AND o.id = l.order_id " +
                    "WHERE o.restaurant_id = ? AND o.session_id = ANY(?::uuid[]) AND o.status NOT IN ('rejected', 'cancelled') " +
                    "AND l.status = 'active' ORDER BY o.placed_at, o.id, l.line_no",
            ).use { statement ->
                statement.bind(restaurantId.value, sessionIds.map(TableSessionId::toString)).list { rs ->
                    TableSessionId(rs.uuid("session_id")!!) to BillLine(OrderId(rs.uuid("order_id")!!), OrderRepository.line(rs))
                }
            }.groupBy({ it.first }, { it.second })
    }

    /** The corrections made on [date], whichever bill they belong to: (method, signed amount), oldest first. */
    fun correctionsOn(
        tx: TxScope,
        restaurantId: RestaurantId,
        date: LocalDate,
    ): List<Pair<PaymentMethod, Long>> =
        connection(tx)
            .prepareStatement(
                "SELECT payment_method, amount FROM bill_adjustments WHERE restaurant_id = ? AND business_date = ? " +
                    "ORDER BY created_at, id",
            ).use { statement ->
                statement.bind(restaurantId.value, date).list { PaymentMethod.of(it.getString("payment_method")) to it.getLong("amount") }
            }

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)
}
