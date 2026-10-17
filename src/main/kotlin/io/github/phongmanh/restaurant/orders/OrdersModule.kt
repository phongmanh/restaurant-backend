package io.github.phongmanh.restaurant.orders

import io.github.phongmanh.restaurant.core.application.RetentionTask
import io.github.phongmanh.restaurant.orders.application.BillPorts
import io.github.phongmanh.restaurant.orders.application.BillService
import io.github.phongmanh.restaurant.orders.application.BusinessDay
import io.github.phongmanh.restaurant.orders.application.NoteRetention
import io.github.phongmanh.restaurant.orders.application.OrderQueryService
import io.github.phongmanh.restaurant.orders.application.OrderRateLimits
import io.github.phongmanh.restaurant.orders.application.OrderStatusService
import io.github.phongmanh.restaurant.orders.application.PlaceOrderService
import io.github.phongmanh.restaurant.orders.application.SalesFactsSource
import io.github.phongmanh.restaurant.orders.http.GuestOrderRoutes
import io.github.phongmanh.restaurant.orders.http.OrderSnapshots
import io.github.phongmanh.restaurant.orders.http.StaffBillRoutes
import io.github.phongmanh.restaurant.orders.http.StaffOrderRoutes
import io.github.phongmanh.restaurant.orders.persistence.BillRepository
import io.github.phongmanh.restaurant.orders.persistence.NoteRepository
import io.github.phongmanh.restaurant.orders.persistence.OrderRepository
import io.github.phongmanh.restaurant.orders.persistence.SalesRowRepository
import io.github.phongmanh.restaurant.realtime.application.Snapshots
import io.github.phongmanh.restaurant.reports.application.SalesSource
import io.github.phongmanh.restaurant.tables.application.EmptyBills
import io.github.phongmanh.restaurant.tables.application.SessionSummaries
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * The orders feature (orders §13): placing orders, the order steps, the bill and its close, and
 * the ports `SessionSummaries` and `EmptyBills` for tables.
 */
fun ordersModule() =
    module {
        single { OrderRepository() }
        single { BillRepository() }
        single { NoteRepository() }

        single { OrderRateLimits(get(), get()) }
        single { BusinessDay(get()) }
        single { BillPorts(get(), get(), get(), get()) } binds arrayOf(SessionSummaries::class, EmptyBills::class)
        single { OrderQueryService(get(), get(), get()) }
        single { PlaceOrderService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { OrderStatusService(get(), get(), get(), get(), get(), get(), get()) }
        single { BillService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { NoteRetention(get(), get(), get()) } bind RetentionTask::class
        single { SalesRowRepository() }
        single<SalesSource> { SalesFactsSource(get(), get()) }
        single { OrderSnapshots(get(), get()) } bind Snapshots::class

        single { GuestOrderRoutes(get(), get(), get(), get(), get(), get()) }
        single { StaffOrderRoutes(get(), get(), get(), get(), get()) }
        single { StaffBillRoutes(get(), get()) }
    }
