package io.github.phongmanh.restaurant.reports

import io.github.phongmanh.restaurant.reports.application.DatabaseSalesLedger
import io.github.phongmanh.restaurant.reports.application.ReportService
import io.github.phongmanh.restaurant.reports.application.SalesLedger
import io.github.phongmanh.restaurant.reports.application.SalesReconcile
import io.github.phongmanh.restaurant.reports.http.ReportJobRoutes
import io.github.phongmanh.restaurant.reports.http.StaffReportRoutes
import io.github.phongmanh.restaurant.reports.persistence.DailySalesRepository
import io.github.phongmanh.restaurant.reports.persistence.ReconciliationRepository
import org.koin.dsl.module

/**
 * The reports feature (reports §8): the `SalesLedger` port that orders calls when a bill closes or
 * is corrected, the report read, and the nightly rebuild, which reads the raw rows through the
 * `SalesSource` port that orders provides.
 */
fun reportsModule() =
    module {
        single { DailySalesRepository() }
        single { ReconciliationRepository() }
        single<SalesLedger> { DatabaseSalesLedger(get()) }
        single { ReportService(get(), get(), get(), get(), get()) }
        single { SalesReconcile(get(), get(), get(), get(), get(), get(), get()) }

        single { StaffReportRoutes(get()) }
        single { ReportJobRoutes(get()) }
    }
