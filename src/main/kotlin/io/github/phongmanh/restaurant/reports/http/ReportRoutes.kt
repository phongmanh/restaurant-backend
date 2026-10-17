package io.github.phongmanh.restaurant.reports.http

import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.FieldError
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.endpoint
import io.github.phongmanh.restaurant.core.http.job
import io.github.phongmanh.restaurant.core.http.staffCaller
import io.github.phongmanh.restaurant.menu.http.MoneyBody
import io.github.phongmanh.restaurant.reports.application.ReportService
import io.github.phongmanh.restaurant.reports.application.SalesReconcile
import io.github.phongmanh.restaurant.reports.domain.DayTotals
import io.github.phongmanh.restaurant.reports.domain.ItemSales
import io.github.phongmanh.restaurant.reports.domain.PaymentFigures
import io.github.phongmanh.restaurant.reports.domain.ReportDay
import io.github.phongmanh.restaurant.reports.domain.ReportRange
import io.github.phongmanh.restaurant.reports.domain.SalesReport
import io.github.phongmanh.restaurant.restaurants.application.RestaurantScope
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** `PaymentSales`. */
@Serializable
data class PaymentSalesBody(
    val method: String,
    val sessions: Int,
    val amount: MoneyBody,
    val corrections: MoneyBody,
) {
    companion object {
        fun of(payments: Map<String, PaymentFigures>) =
            payments.map { (method, p) -> PaymentSalesBody(method, p.sessions, MoneyBody.of(p.amount), MoneyBody.of(p.corrections)) }
    }
}

/** `TableSales`: the number is the table's as it was that day. */
@Serializable
data class TableSalesBody(
    @SerialName("table_id") val tableId: String,
    val number: String,
    val sessions: Int,
    val gross: MoneyBody,
)

/** `ItemSales`: the names are the order lines' snapshot. */
@Serializable
data class ItemSalesBody(
    @SerialName("item_id") val itemId: String,
    @SerialName("name_vi") val nameVi: String,
    @SerialName("name_en") val nameEn: String? = null,
    val quantity: Int,
    val revenue: MoneyBody,
) {
    companion object {
        fun of(item: ItemSales) =
            item.figures.let { ItemSalesBody(item.itemId.toString(), it.nameVi, it.nameEn, it.quantity, MoneyBody.of(it.revenue)) }
    }
}

/** `SalesTotals`: for the whole range. Net = gross + corrections (reports §13 Q1). */
@Serializable
data class SalesTotalsBody(
    val sessions: Int,
    val orders: Int,
    val gross: MoneyBody,
    val corrections: MoneyBody,
    val net: MoneyBody,
    val payments: List<PaymentSalesBody>,
) {
    companion object {
        fun of(
            totals: DayTotals,
            payments: Map<String, PaymentFigures>,
        ) = SalesTotalsBody(
            totals.sessions,
            totals.orders,
            MoneyBody.of(totals.gross),
            MoneyBody.of(totals.corrections),
            MoneyBody.of(totals.net),
            PaymentSalesBody.of(payments),
        )
    }
}

/** `SalesDay`: `SalesTotals` for one day, with its corrections count, tables and top items. */
@Serializable
data class SalesDayBody(
    val date: String,
    val sessions: Int,
    val orders: Int,
    val gross: MoneyBody,
    val corrections: MoneyBody,
    @SerialName("correction_count") val correctionCount: Int,
    val net: MoneyBody,
    val payments: List<PaymentSalesBody>,
    val tables: List<TableSalesBody>,
    @SerialName("top_items_by_quantity") val topItemsByQuantity: List<ItemSalesBody>,
    @SerialName("top_items_by_revenue") val topItemsByRevenue: List<ItemSalesBody>,
) {
    companion object {
        fun of(day: ReportDay) =
            SalesDayBody(
                date = day.date.toString(),
                sessions = day.totals.sessions,
                orders = day.totals.orders,
                gross = MoneyBody.of(day.totals.gross),
                corrections = MoneyBody.of(day.totals.corrections),
                correctionCount = day.totals.correctionCount,
                net = MoneyBody.of(day.totals.net),
                payments = PaymentSalesBody.of(day.payments),
                tables =
                    day.tables.map {
                        TableSalesBody(
                            it.tableId.toString(),
                            it.figures.number,
                            it.figures.sessions,
                            MoneyBody.of(it.figures.gross),
                        )
                    },
                topItemsByQuantity = day.topByQuantity.map(ItemSalesBody::of),
                topItemsByRevenue = day.topByRevenue.map(ItemSalesBody::of),
            )
    }
}

/** `DailySalesReport` (reports §6). */
@Serializable
data class DailySalesReportBody(
    val from: String,
    val to: String,
    @SerialName("time_zone") val timeZone: String,
    val totals: SalesTotalsBody,
    val days: List<SalesDayBody>,
) {
    companion object {
        fun of(report: SalesReport) =
            DailySalesReportBody(
                from = report.range.from.toString(),
                to = report.range.to.toString(),
                timeZone = report.timeZone,
                totals = SalesTotalsBody.of(report.totals, report.payments),
                days = report.days.map(SalesDayBody::of),
            )
    }
}

/**
 * The report route (reports §6–7): managers only, allowed while the restaurant is suspended. The
 * role check, then the rate limit, then the query: 401 → 403 → 429 → 400.
 */
class StaffReportRoutes(
    private val reports: ReportService,
) {
    fun install(route: Route) {
        route.endpoint(Endpoints.Reports.DAILY_SALES_REPORT) {
            val caller = call.staffCaller()
            val scope = RestaurantScope.manager(caller)
            reports.limit(caller)
            call.respond(DailySalesReportBody.of(reports.dailySales(scope, call.reportRange())))
        }
    }
}

/** Reads `from` and `to` (local dates, both required) and checks the range. */
private fun ApplicationCall.reportRange(): ReportRange {
    fun date(name: String): Pair<LocalDate?, FieldError?> {
        val value = request.queryParameters[name] ?: return null to FieldError("query.$name", "required")
        return try {
            LocalDate.parse(value) to null
        } catch (_: DateTimeParseException) {
            null to FieldError("query.$name", "invalid_date", "a date such as 2026-10-01")
        }
    }
    val (from, fromError) = date("from")
    val (to, toError) = date("to")
    val errors = listOfNotNull(fromError, toError)
    if (errors.isNotEmpty()) throw DomainError.ValidationFailed(errors)
    return ReportRange.of(from!!, to!!)
}

/** What a rebuild run answers Vercel Cron. Not part of the contract. */
@Serializable
data class ReconcileResult(
    val restaurants: Int,
    val days: Int,
    val mismatches: Int,
    val failed: Int,
    @SerialName("lag_days") val lagDays: Long,
)

/** The nightly rebuild (reports §5), behind the cron secret. */
class ReportJobRoutes(
    private val reconcile: SalesReconcile,
) {
    fun install(route: Route) {
        route.job(Endpoints.Jobs.SALES_RECONCILE) {
            val run = reconcile.run()
            call.respond(ReconcileResult(run.restaurants, run.days, run.mismatches, run.failed, run.lagDays))
        }
    }
}
