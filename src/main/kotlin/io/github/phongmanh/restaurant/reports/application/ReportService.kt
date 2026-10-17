package io.github.phongmanh.restaurant.reports.application

import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.RateLimits
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.auth.RateLimitSettings
import io.github.phongmanh.restaurant.reports.domain.DayRows
import io.github.phongmanh.restaurant.reports.domain.DayTotals
import io.github.phongmanh.restaurant.reports.domain.ItemSales
import io.github.phongmanh.restaurant.reports.domain.ReportDay
import io.github.phongmanh.restaurant.reports.domain.ReportRange
import io.github.phongmanh.restaurant.reports.domain.SalesReport
import io.github.phongmanh.restaurant.reports.domain.TableSales
import io.github.phongmanh.restaurant.reports.persistence.DailySalesRepository
import io.github.phongmanh.restaurant.restaurants.application.RestaurantDirectory
import io.github.phongmanh.restaurant.restaurants.application.RestaurantScope
import java.time.LocalDate

/** The rate-limit policy of this feature (reports §7). */
object ReportLimits {
    /** Report reads per staff member: 30 a minute, which only stops a runaway client. */
    const val READ = "reports.read"
}

/**
 * The daily sales report (reports §6): one query over the four daily tables, so a month costs a few
 * milliseconds however many bills it holds. Every day in the range is listed, with zeros where
 * nothing sold, and today shows the bills closed so far.
 */
class ReportService(
    private val transactor: Transactor,
    private val sales: DailySalesRepository,
    private val directory: RestaurantDirectory,
    private val limits: RateLimits,
    private val settings: RateLimitSettings,
) {
    /** Over the limit → `429` with `Retry-After`. Called after the role check, before the query is read. */
    suspend fun limit(caller: Caller.Staff) = limits.check(settings.policy(ReportLimits.READ), caller.staffId.toString())

    suspend fun dailySales(
        scope: RestaurantScope,
        range: ReportRange,
    ): SalesReport =
        transactor.inTransaction(scope.restaurantId) {
            val zone = directory.profile(this, scope.restaurantId)?.timeZone ?: error("no restaurant ${scope.restaurantId}")
            val rows = sales.range(this, scope.restaurantId, range.from, range.to, SalesReport.TOP_ITEMS)
            SalesReport(range, zone, range.days.map { day(it, rows[it] ?: DayRows.EMPTY) })
        }

    private fun day(
        date: LocalDate,
        rows: DayRows,
    ): ReportDay {
        val items = rows.items.map { (id, figures) -> ItemSales(id, figures) }
        return ReportDay(
            date = date,
            totals = rows.totals ?: DayTotals(),
            payments = rows.payments.toSortedMap(),
            tables =
                rows.tables
                    .map { (id, figures) ->
                        TableSales(id, figures)
                    }.sortedWith(compareBy({ it.figures.number }, { it.tableId.toString() })),
            topByQuantity =
                items
                    .sortedWith(
                        compareByDescending<ItemSales> { it.figures.quantity }.thenBy { it.itemId.toString() },
                    ).take(SalesReport.TOP_ITEMS),
            topByRevenue =
                items
                    .sortedWith(
                        compareByDescending<ItemSales> { it.figures.revenue }.thenBy { it.itemId.toString() },
                    ).take(SalesReport.TOP_ITEMS),
        )
    }
}
