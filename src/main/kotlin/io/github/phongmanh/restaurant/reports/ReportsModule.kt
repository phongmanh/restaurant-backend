package io.github.phongmanh.restaurant.reports

import io.github.phongmanh.restaurant.reports.application.DatabaseSalesLedger
import io.github.phongmanh.restaurant.reports.application.SalesLedger
import io.github.phongmanh.restaurant.reports.persistence.DailySalesRepository
import org.koin.dsl.module

/**
 * The reports feature (reports §8). Its write half, the `SalesLedger` port that orders calls, is
 * built with orders (step 7); the report read and the nightly rebuild come in step 9.
 */
fun reportsModule() =
    module {
        single { DailySalesRepository() }
        single<SalesLedger> { DatabaseSalesLedger(get()) }
    }
