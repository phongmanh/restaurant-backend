package io.github.phongmanh.restaurant.reports.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.reports.application.ClosedBillSale
import io.github.phongmanh.restaurant.reports.application.ItemSale
import java.time.LocalDate

/**
 * The daily aggregates (reports §3). Every method takes the restaurant. Each one upserts: it adds
 * to the day's row, creating it on the day's first bill (`ON CONFLICT … DO UPDATE SET x = t.x +
 * excluded.x`), and sets the display names to the latest values.
 */
class DailySalesRepository {
    fun addBill(
        tx: TxScope,
        restaurantId: RestaurantId,
        sale: ClosedBillSale,
    ) = update(
        tx,
        "INSERT INTO daily_sales AS t (restaurant_id, sales_date, sessions, orders, gross) VALUES (?, ?, 1, ?, ?) " +
            "ON CONFLICT (restaurant_id, sales_date) DO UPDATE SET sessions = t.sessions + 1, orders = t.orders + excluded.orders, " +
            "gross = t.gross + excluded.gross",
        restaurantId.value,
        sale.businessDate,
        sale.orderCount,
        sale.total,
    )

    fun addPayment(
        tx: TxScope,
        restaurantId: RestaurantId,
        sale: ClosedBillSale,
    ) = update(
        tx,
        "INSERT INTO daily_sales_payment AS t (restaurant_id, sales_date, method, sessions, amount) VALUES (?, ?, ?, 1, ?) " +
            "ON CONFLICT (restaurant_id, sales_date, method) DO UPDATE SET sessions = t.sessions + 1, amount = t.amount + excluded.amount",
        restaurantId.value,
        sale.businessDate,
        sale.paymentMethod,
        sale.total,
    )

    fun addTable(
        tx: TxScope,
        restaurantId: RestaurantId,
        sale: ClosedBillSale,
    ) = update(
        tx,
        "INSERT INTO daily_sales_table AS t (restaurant_id, sales_date, table_id, table_number, sessions, gross) " +
            "VALUES (?, ?, ?, ?, 1, ?) ON CONFLICT (restaurant_id, sales_date, table_id) DO UPDATE SET " +
            "table_number = excluded.table_number, sessions = t.sessions + 1, gross = t.gross + excluded.gross",
        restaurantId.value,
        sale.businessDate,
        sale.tableId.value,
        sale.tableNumber,
        sale.total,
    )

    /** One upsert per item, in the order given (by item ID, so concurrent closes queue). */
    fun addItems(
        tx: TxScope,
        restaurantId: RestaurantId,
        date: LocalDate,
        items: List<ItemSale>,
    ) {
        if (items.isEmpty()) return
        ExposedTransactor
            .connection(tx)
            .prepareStatement(
                "INSERT INTO daily_sales_item AS t (restaurant_id, sales_date, item_id, name_vi, name_en, quantity, revenue) " +
                    "VALUES (?, ?, ?, ?, ?::text, ?, ?) ON CONFLICT (restaurant_id, sales_date, item_id) DO UPDATE SET " +
                    "name_vi = excluded.name_vi, name_en = excluded.name_en, quantity = t.quantity + excluded.quantity, " +
                    "revenue = t.revenue + excluded.revenue",
            ).use { statement ->
                items.forEach {
                    statement.bind(restaurantId.value, date, it.itemId, it.nameVi, it.nameEn, it.quantity, it.revenue).addBatch()
                }
                statement.executeBatch()
            }
    }

    fun addCorrection(
        tx: TxScope,
        restaurantId: RestaurantId,
        date: LocalDate,
        amount: Long,
    ) = update(
        tx,
        "INSERT INTO daily_sales AS t (restaurant_id, sales_date, corrections, correction_count) VALUES (?, ?, ?, 1) " +
            "ON CONFLICT (restaurant_id, sales_date) DO UPDATE SET corrections = t.corrections + excluded.corrections, " +
            "correction_count = t.correction_count + 1",
        restaurantId.value,
        date,
        amount,
    )

    fun addPaymentCorrection(
        tx: TxScope,
        restaurantId: RestaurantId,
        date: LocalDate,
        method: String,
        amount: Long,
    ) = update(
        tx,
        "INSERT INTO daily_sales_payment AS t (restaurant_id, sales_date, method, corrections) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (restaurant_id, sales_date, method) DO UPDATE SET corrections = t.corrections + excluded.corrections",
        restaurantId.value,
        date,
        method,
        amount,
    )

    private fun update(
        tx: TxScope,
        sql: String,
        vararg values: Any?,
    ) {
        ExposedTransactor.connection(tx).prepareStatement(sql).use { it.bind(*values).executeUpdate() }
    }
}
