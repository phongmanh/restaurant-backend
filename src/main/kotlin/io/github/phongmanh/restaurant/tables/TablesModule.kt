package io.github.phongmanh.restaurant.tables

import io.github.phongmanh.restaurant.core.auth.GuestSettings
import io.github.phongmanh.restaurant.core.auth.application.GuestAuthenticator
import io.github.phongmanh.restaurant.realtime.application.Snapshots
import io.github.phongmanh.restaurant.tables.application.DatabaseSessionAccess
import io.github.phongmanh.restaurant.tables.application.FloorViewService
import io.github.phongmanh.restaurant.tables.application.GuestAuthentication
import io.github.phongmanh.restaurant.tables.application.GuestSessionService
import io.github.phongmanh.restaurant.tables.application.QrService
import io.github.phongmanh.restaurant.tables.application.ScanLimits
import io.github.phongmanh.restaurant.tables.application.SessionAccess
import io.github.phongmanh.restaurant.tables.application.SessionAutoClose
import io.github.phongmanh.restaurant.tables.application.SessionOpener
import io.github.phongmanh.restaurant.tables.application.StaffSessionService
import io.github.phongmanh.restaurant.tables.application.TableService
import io.github.phongmanh.restaurant.tables.application.WaiterCallService
import io.github.phongmanh.restaurant.tables.crypto.GuestTokens
import io.github.phongmanh.restaurant.tables.crypto.QrCodes
import io.github.phongmanh.restaurant.tables.http.GuestSessionRoutes
import io.github.phongmanh.restaurant.tables.http.StaffSessionRoutes
import io.github.phongmanh.restaurant.tables.http.StaffTableRoutes
import io.github.phongmanh.restaurant.tables.http.TableJobRoutes
import io.github.phongmanh.restaurant.tables.http.WaiterCallSnapshots
import io.github.phongmanh.restaurant.tables.persistence.IdleSessionRepository
import io.github.phongmanh.restaurant.tables.persistence.SessionRepository
import io.github.phongmanh.restaurant.tables.persistence.TableRepository
import io.github.phongmanh.restaurant.tables.persistence.WaiterCallRepository
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * The tables feature (tables §10): dining tables, QR codes, table sessions and the guest token,
 * waiter calls, the floor view, and the `SessionAccess` port for orders.
 */
fun tablesModule(
    guests: GuestSettings,
    issuer: String,
) = module {
    single { QrCodes(guests.qrKeys, guests.customerAppUrl) }
    single { GuestTokens(guests.sessionKeys, issuer, get()) }

    single { TableRepository() }
    single { SessionRepository() }
    single { IdleSessionRepository() }
    single { WaiterCallRepository() }

    // SessionSummaries and EmptyBills come from the orders module (orders §11).
    single<SessionAccess> { DatabaseSessionAccess(get(), get(), get()) }
    single<GuestAuthenticator> { GuestAuthentication(get(), get(), get()) }
    single { SessionOpener(get(), get(), get()) }
    single { ScanLimits(get(), get()) }
    single { TableService(get(), get(), get(), get(), get(), get()) }
    single { QrService(get(), get(), get(), get(), get()) }
    single { GuestSessionService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { StaffSessionService(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { WaiterCallService(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { FloorViewService(get(), get(), get(), get(), get(), get()) }
    single { SessionAutoClose(get(), get(), get(), get(), get(), get()) }
    single { WaiterCallSnapshots(get()) } bind Snapshots::class

    single { GuestSessionRoutes(get(), get()) }
    single { StaffTableRoutes(get(), get(), get()) }
    single { StaffSessionRoutes(get(), get()) }
    single { TableJobRoutes(get()) }
}
