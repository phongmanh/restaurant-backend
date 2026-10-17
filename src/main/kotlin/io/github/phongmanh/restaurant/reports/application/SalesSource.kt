package io.github.phongmanh.restaurant.reports.application

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.reports.domain.DayRows
import io.github.phongmanh.restaurant.reports.domain.DayTotals
import io.github.phongmanh.restaurant.reports.domain.ItemFigures
import io.github.phongmanh.restaurant.reports.domain.PaymentFigures
import io.github.phongmanh.restaurant.reports.domain.TableFigures
import java.time.LocalDate
import java.util.UUID

/** A correction to a closed bill, on the day it was made (orders §7.2). */
data class SalesCorrection(
    /** `cash`, `card` or `bank_transfer`. */
    val paymentMethod: String,
    /** Signed, in whole đồng. */
    val amount: Long,
)

/** What a day's sales were, from the raw rows (reports §5 step 1). */
data class SalesFacts(
    /** The bills closed with a payment on that local day, in the order they were closed. */
    val bills: List<ClosedBillSale>,
    val corrections: List<SalesCorrection>,
)

/**
 * The `SalesSource` port (reports §5), provided by `orders`: one local day's closed bills and
 * corrections, exactly as the close and the correction passed them to the [SalesLedger]. `reports`
 * doesn't read the order tables itself (architecture §4).
 */
fun interface SalesSource {
    fun facts(
        tx: TxScope,
        restaurantId: RestaurantId,
        date: LocalDate,
    ): SalesFacts
}

/**
 * The rows the [SalesLedger] would have written for [facts]: the same sums, the same rows (a
 * method or a day that only had corrections has a row too), and the names of the last bill.
 */
fun tally(facts: SalesFacts): DayRows {
    if (facts.bills.isEmpty() && facts.corrections.isEmpty()) return DayRows.EMPTY
    var totals = DayTotals()
    val payments = sortedMapOf<String, PaymentFigures>()
    val tables = sortedMapOf<UUID, TableFigures>()
    val items = sortedMapOf<UUID, ItemFigures>()
    facts.bills.forEach { bill ->
        totals = totals + DayTotals(sessions = 1, orders = bill.orderCount, gross = bill.total)
        payments.merge(bill.paymentMethod, PaymentFigures(1, bill.total, 0), PaymentFigures::plus)
        tables.merge(bill.tableId.value, TableFigures(bill.tableNumber, 1, bill.total)) { old, new ->
            TableFigures(new.number, old.sessions + 1, old.gross + new.gross)
        }
        bill.items.forEach { item ->
            items.merge(item.itemId, ItemFigures(item.nameVi, item.nameEn, item.quantity, item.revenue)) { old, new ->
                new.copy(quantity = old.quantity + new.quantity, revenue = old.revenue + new.revenue)
            }
        }
    }
    facts.corrections.forEach { c ->
        totals = totals + DayTotals(corrections = c.amount, correctionCount = 1)
        payments.merge(c.paymentMethod, PaymentFigures(corrections = c.amount), PaymentFigures::plus)
    }
    return DayRows(totals, payments, tables, items)
}
