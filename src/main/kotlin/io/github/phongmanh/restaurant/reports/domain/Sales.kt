package io.github.phongmanh.restaurant.reports.domain

import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.FieldError
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A day's row of `daily_sales` (reports §3). */
data class DayTotals(
    /** Bills closed with a payment (§13 Q2). */
    val sessions: Int = 0,
    val orders: Int = 0,
    val gross: Long = 0,
    /** The signed sum of the corrections made that day. */
    val corrections: Long = 0,
    val correctionCount: Int = 0,
) {
    val net: Long get() = gross + corrections

    operator fun plus(o: DayTotals) =
        DayTotals(
            sessions + o.sessions,
            orders + o.orders,
            gross + o.gross,
            corrections + o.corrections,
            correctionCount + o.correctionCount,
        )
}

/** A day's row of `daily_sales_payment`: the bills paid with one method, and the corrections booked to it. */
data class PaymentFigures(
    val sessions: Int = 0,
    val amount: Long = 0,
    val corrections: Long = 0,
) {
    operator fun plus(o: PaymentFigures) = PaymentFigures(sessions + o.sessions, amount + o.amount, corrections + o.corrections)
}

/** A day's row of `daily_sales_table`; [number] is the table's number at the last close that day. */
data class TableFigures(
    val number: String,
    val sessions: Int,
    val gross: Long,
)

/** A day's row of `daily_sales_item`; the names are the order lines' snapshot. */
data class ItemFigures(
    val nameVi: String,
    val nameEn: String?,
    val quantity: Int,
    val revenue: Long,
)

/**
 * One day's rows in the four daily tables (reports §3), keyed as the tables are. The nightly
 * rebuild (§5) computes the same shape from the closed bills and compares the two.
 */
data class DayRows(
    /** `null` when nothing was closed or corrected that day: the table has no row. */
    val totals: DayTotals?,
    /** By method: `cash`, `card` or `bank_transfer`. */
    val payments: Map<String, PaymentFigures>,
    val tables: Map<UUID, TableFigures>,
    val items: Map<UUID, ItemFigures>,
) {
    /**
     * Whether the figures agree, leaving the display names aside: those are the latest snapshot,
     * which the closes set and a rebuild can only approximate (§3).
     */
    fun sameFiguresAs(other: DayRows): Boolean = withoutNames() == other.withoutNames()

    /** These figures, with [stored]'s names where it has the row, so a rebuild keeps the names a close wrote. */
    fun keepingNamesOf(stored: DayRows): DayRows =
        copy(
            tables = tables.mapValues { (id, t) -> stored.tables[id]?.let { t.copy(number = it.number) } ?: t },
            items = items.mapValues { (id, i) -> stored.items[id]?.let { i.copy(nameVi = it.nameVi, nameEn = it.nameEn) } ?: i },
        )

    private fun withoutNames() =
        copy(
            tables = tables.mapValues { it.value.copy(number = "") },
            items = items.mapValues { it.value.copy(nameVi = "", nameEn = null) },
        )

    companion object {
        val EMPTY = DayRows(null, emptyMap(), emptyMap(), emptyMap())
    }
}

/**
 * The days of a report (FR-050): local dates in the restaurant's time zone, `from` to `to`
 * included, at most [MAX_DAYS]. Otherwise `400 validation_failed` with `range_too_long` or
 * `end_before_start` (reports §6–7).
 */
class ReportRange private constructor(
    val from: LocalDate,
    val to: LocalDate,
) {
    val days: List<LocalDate> get() = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()

    companion object {
        const val MAX_DAYS = 93

        fun of(
            from: LocalDate,
            to: LocalDate,
        ): ReportRange {
            val error =
                when {
                    to.isBefore(from) -> FieldError("query.to", "end_before_start", "to must not be before from")
                    ChronoUnit.DAYS.between(from, to) >= MAX_DAYS -> FieldError("query.to", "range_too_long", "at most $MAX_DAYS days")
                    else -> null
                }
            error?.let { throw DomainError.ValidationFailed(listOf(it)) }
            return ReportRange(from, to)
        }
    }
}

/** A table's line in a report day. */
data class TableSales(
    val tableId: UUID,
    val figures: TableFigures,
)

/** An item's line in a report day's top lists. */
data class ItemSales(
    val itemId: UUID,
    val figures: ItemFigures,
)

/** One day of the report (reports §6): every day in the range has one, with zeros where nothing sold. */
data class ReportDay(
    val date: LocalDate,
    val totals: DayTotals,
    /** By method, in method order. */
    val payments: Map<String, PaymentFigures>,
    /** By table number, then ID. */
    val tables: List<TableSales>,
    /** 10 each; ties break on the item ID, so the order is stable. */
    val topByQuantity: List<ItemSales>,
    val topByRevenue: List<ItemSales>,
)

/** `DailySalesReport` (reports §6). The range totals are sums of the days. */
data class SalesReport(
    val range: ReportRange,
    val timeZone: String,
    val days: List<ReportDay>,
) {
    val totals: DayTotals get() = days.fold(DayTotals()) { sum, day -> sum + day.totals }

    val payments: Map<String, PaymentFigures>
        get() =
            days
                .flatMap { it.payments.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, figures) -> figures.fold(PaymentFigures()) { sum, f -> sum + f } }
                .toSortedMap()

    companion object {
        /** The number of items in each of a day's top lists. */
        const val TOP_ITEMS = 10
    }
}
