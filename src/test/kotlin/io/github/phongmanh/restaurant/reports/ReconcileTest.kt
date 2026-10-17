package io.github.phongmanh.restaurant.reports

import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.orders.floor
import io.github.phongmanh.restaurant.orders.int
import io.github.phongmanh.restaurant.orders.line
import io.github.phongmanh.restaurant.orders.ordersApp
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Guests
import io.github.phongmanh.restaurant.support.MutableClock
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.str
import io.github.phongmanh.restaurant.tables.job
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** IT-REP-06 and IT-REP-07: the nightly rebuild (reports §5). */
class ReconcileTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    @Test
    fun `IT-REP-06 tallies broken for a day are found, counted and repaired, and a second run changes nothing`() {
        val clock = MutableClock()
        ordersApp(clock) {
            val f = client.floor()
            val zone = zoneWhere(clock.now, LocalTime.NOON)
            setTimeZone(f.id, zone.id)
            val today = LocalDate.ofInstant(clock.now, zone)
            val yesterday = today.minusDays(1)
            val closed = client.sell(f, f.seat, line(f.pho, 2, listOf(f.large)), line(f.tea))
            client.sell(f, Guests.seated(f.id), line(f.tea, 3), method = "card")
            client.correct(f, f.session, closed.int("version"), -5000)
            moveDay(f.id, today, yesterday)
            val good = dailyRows(f.id, yesterday)
            assertEquals(1 + 2 + 2 + 2, good.size, "a day, two methods, two tables, two items")

            // A tally that missed updates: wrong figures, a missing item, a missing table, a payment row that shouldn't be.
            AuthFixtures.sql("UPDATE daily_sales SET gross = gross + 1000, correction_count = 0 WHERE restaurant_id = ?", f.id.value)
            AuthFixtures.sql("DELETE FROM daily_sales_item WHERE restaurant_id = ? AND name_vi = 'Trà đá'", f.id.value)
            AuthFixtures.sql("DELETE FROM daily_sales_table WHERE restaurant_id = ? AND table_id = ?", f.id.value, f.seat.table.value)
            AuthFixtures.sql(
                "INSERT INTO daily_sales_payment (restaurant_id, sales_date, method, sessions, amount) " +
                    "VALUES (?, ?, 'bank_transfer', 1, 9)",
                f.id.value,
                yesterday,
            )
            assertEquals(HttpStatusCode.Unauthorized, client.job(Endpoints.Jobs.SALES_RECONCILE, null).status)
            val run = client.reconcile()
            assertEquals(listOf("1", "2", "1", "0"), listOf("restaurants", "days", "mismatches", "failed").map(run::str))
            assertEquals(good, dailyRows(f.id, yesterday), "repaired")
            assertEquals("$yesterday $yesterday", reconciliation(f.id))

            assertEquals("0", client.reconcile().str("restaurants"), "reconciled through yesterday: nothing to do")
            AuthFixtures.sql("DELETE FROM sales_reconciliation WHERE restaurant_id = ?", f.id.value)
            val again = client.reconcile()
            assertEquals(listOf("1", "0"), listOf("restaurants", "mismatches").map(again::str))
            assertEquals(good, dailyRows(f.id, yesterday), "running it again changes nothing")
        }
    }

    @Test
    fun `IT-REP-07 each restaurant is rebuilt after its own 03_00`() {
        // 07:30 UTC is 14:30 in Hồ Chí Minh City (UTC+7) and 02:30 in Bogotá (UTC−5, no summer time).
        val clock = MutableClock(Instant.parse("2026-10-10T07:30:00Z"))
        ordersApp(clock) {
            val hcm = AuthFixtures.restaurant("Phở Sài Gòn")
            val bogota = AuthFixtures.restaurant("Arepas")
            setTimeZone(hcm, "Asia/Ho_Chi_Minh")
            setTimeZone(bogota, "America/Bogota")

            assertEquals("1", client.reconcile().str("restaurants"))
            assertEquals("2026-10-09 -", reconciliation(hcm))
            assertNull(reconciliation(bogota), "02:30 in Bogotá: its night isn't over")

            clock.now = Instant.parse("2026-10-10T08:30:00Z")
            assertEquals("1", client.reconcile().str("restaurants"))
            assertEquals("2026-10-09 -", reconciliation(bogota))
            assertEquals("0", client.reconcile().str("restaurants"))

            clock.now = Instant.parse("2026-10-11T07:30:00Z")
            val next = client.reconcile()
            assertEquals(listOf("1", "1"), listOf("restaurants", "lag_days").map(next::str), "Bogotá is a day behind until its 03:00")
            assertEquals("2026-10-10 -", reconciliation(hcm))
        }
    }
}
