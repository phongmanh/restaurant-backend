package io.github.phongmanh.restaurant.reports.domain

import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.reports.application.ReconcileRules
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The report range (FR-050) and the rebuild's timing (reports §5), without a database. */
class ReportRulesTest {
    private val d = LocalDate.parse("2026-10-10")

    @Test
    fun `a range is 1 to 93 days, from before to`() {
        assertEquals(listOf(d), ReportRange.of(d, d).days)
        assertEquals(93, ReportRange.of(d, d.plusDays(92)).days.size)
        assertEquals(
            "range_too_long",
            assertFailsWith<DomainError.ValidationFailed> { ReportRange.of(d, d.plusDays(93)) }.errors.single().code,
        )
        assertEquals(
            "end_before_start",
            assertFailsWith<DomainError.ValidationFailed> { ReportRange.of(d, d.minusDays(1)) }.errors.single().code,
        )
    }

    @Test
    fun `a restaurant is due after its 03_00, for yesterday and the day before, until it's reconciled through yesterday`() {
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        val before = Instant.parse("2026-10-10T19:59:00Z") // 02:59 on the 11th
        val after = Instant.parse("2026-10-10T20:00:00Z") // 03:00 on the 11th
        assertEquals(emptyList(), ReconcileRules.daysDue(zone, null, before))
        assertEquals(listOf(d.minusDays(1), d), ReconcileRules.daysDue(zone, null, after))
        assertEquals(listOf(d.minusDays(1), d), ReconcileRules.daysDue(zone, d.minusDays(1), after))
        assertEquals(emptyList(), ReconcileRules.daysDue(zone, d, after))
        assertEquals(0, ReconcileRules.lagDays(zone, d, after))
        assertEquals(3, ReconcileRules.lagDays(zone, d.minusDays(3), after))
    }
}
