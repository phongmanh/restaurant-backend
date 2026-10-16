package io.github.phongmanh.restaurant.support

import io.github.phongmanh.restaurant.core.domain.DeviceId
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.TableId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.tables.crypto.GuestClaims
import io.github.phongmanh.restaurant.tables.crypto.GuestTokens
import io.github.phongmanh.restaurant.tables.crypto.QrCodes
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A guest at a table: the table, its open session and a session token for one device. */
data class Seat(
    val restaurant: RestaurantId,
    val table: TableId,
    val session: TableSessionId,
    val token: String,
)

/**
 * Guests without the scan, made in SQL, for tests of other features that only need a guest caller
 * (the menu's guest read, later orders). The tables tests scan real QR codes instead.
 */
object Guests {
    val codes = QrCodes(TestKeys.guests.qrKeys, TestKeys.CUSTOMER_APP)

    /** A new table of [restaurant] with an open session, and a guest token for it. */
    fun seated(restaurant: RestaurantId): Seat {
        val table = UuidV7.next()
        val session = UuidV7.next()
        AuthFixtures.sql(
            "INSERT INTO dining_tables (id, restaurant_id, number, created_at, updated_at) VALUES (?, ?, ?, now(), now())",
            table,
            restaurant.value,
            "T-" + table.toString().takeLast(8),
        )
        AuthFixtures.sql(
            "INSERT INTO table_sessions (id, restaurant_id, table_id, status, opened_at, last_activity_at) " +
                "VALUES (?, ?, ?, 'open', now(), now())",
            session,
            restaurant.value,
            table,
        )
        return Seat(restaurant, TableId(table), TableSessionId(session), token(restaurant, TableSessionId(session)))
    }

    /** A guest token for [session], issued at [issuedAt] (now by default). */
    fun token(
        restaurant: RestaurantId,
        session: TableSessionId,
        device: DeviceId = DeviceId(UuidV7.next()),
        issuedAt: Instant = Instant.now(),
    ): String =
        GuestTokens(
            TestKeys.guests.sessionKeys,
            TestKeys.ISSUER,
            Clock.fixed(issuedAt, ZoneOffset.UTC),
        ).sign(GuestClaims(device, session, restaurant))

    fun closeSession(session: TableSessionId) =
        AuthFixtures.sql(
            "UPDATE table_sessions SET status = 'closed', closed_at = now(), close_reason = 'paid' WHERE id = ?",
            session.value,
        )
}
