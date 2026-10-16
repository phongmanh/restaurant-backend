package io.github.phongmanh.restaurant.reports.application

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.TableId
import io.github.phongmanh.restaurant.reports.persistence.DailySalesRepository
import java.time.LocalDate
import java.util.UUID

/** One item of a closed bill, summed over its active lines by `orders` (reports §4). */
data class ItemSale(
    val itemId: UUID,
    val nameVi: String,
    val nameEn: String?,
    val quantity: Int,
    /** Line totals, options included, in whole đồng. */
    val revenue: Long,
)

/** A bill closed with a payment, as the daily aggregates count it (reports §4, §13 Q2). */
data class ClosedBillSale(
    /** The local day of the close (ASSUMPTION-11). */
    val businessDate: LocalDate,
    val tableId: TableId,
    /** The table's number at the close, for display. */
    val tableNumber: String,
    /** `cash`, `card` or `bank_transfer`. */
    val paymentMethod: String,
    val total: Long,
    val orderCount: Int,
    val items: List<ItemSale>,
)

/**
 * The `SalesLedger` port (reports §4), provided by `reports` to `orders`, which calls it inside the
 * close or correction transaction (orders §7.3). S-5 measured ≈ +2 ms per close.
 *
 * **In plain words:** each day keeps running totals. A close adds its bill to that day's totals
 * at once, so today's report needs no sum over the bills. The nightly rebuild (step 9) checks them.
 */
interface SalesLedger {
    /**
     * Adds a closed bill to its day: one upsert into each of the four daily tables, in a fixed
     * order and, within a table, by key. Two bills closing at once then lock the shared rows in the
     * same order and queue instead of deadlocking. Call it after the order rows and before any
     * event (lock order, CLAUDE.md).
     */
    fun recordClosedBill(
        tx: TxScope,
        restaurantId: RestaurantId,
        sale: ClosedBillSale,
    )

    /** Adds a correction (signed) to the day it's made, overall and to its payment method (orders §7.2). */
    fun recordCorrection(
        tx: TxScope,
        restaurantId: RestaurantId,
        businessDate: LocalDate,
        paymentMethod: String,
        amount: Long,
    )
}

/** [SalesLedger] on `daily_sales`, `daily_sales_payment`, `daily_sales_table` and `daily_sales_item`. */
class DatabaseSalesLedger(
    private val sales: DailySalesRepository,
) : SalesLedger {
    override fun recordClosedBill(
        tx: TxScope,
        restaurantId: RestaurantId,
        sale: ClosedBillSale,
    ) {
        require(tx.restaurantId == restaurantId) { "sales are written in their own restaurant's transaction" }
        // The fixed order of reports §4: day, payment method, table, then items by ID.
        sales.addBill(tx, restaurantId, sale)
        sales.addPayment(tx, restaurantId, sale)
        sales.addTable(tx, restaurantId, sale)
        sales.addItems(tx, restaurantId, sale.businessDate, sale.items.sortedBy { it.itemId })
    }

    override fun recordCorrection(
        tx: TxScope,
        restaurantId: RestaurantId,
        businessDate: LocalDate,
        paymentMethod: String,
        amount: Long,
    ) {
        require(tx.restaurantId == restaurantId) { "sales are written in their own restaurant's transaction" }
        sales.addCorrection(tx, restaurantId, businessDate, amount)
        sales.addPaymentCorrection(tx, restaurantId, businessDate, paymentMethod, amount)
    }
}
