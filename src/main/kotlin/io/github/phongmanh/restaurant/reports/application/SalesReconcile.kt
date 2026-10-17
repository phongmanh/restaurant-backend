package io.github.phongmanh.restaurant.reports.application

import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.persistence.tryAdvisoryLock
import io.github.phongmanh.restaurant.reports.persistence.DailySalesRepository
import io.github.phongmanh.restaurant.reports.persistence.ReconciliationRepository
import io.github.phongmanh.restaurant.restaurants.application.RestaurantZones
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException

/** The rebuild's timing (reports §5). */
object ReconcileRules {
    /** A restaurant's night is over at this local time: its yesterday can be rebuilt. */
    val NIGHT_ENDS: LocalTime = LocalTime.parse("03:00")

    /** Yesterday, and the day before as a margin. */
    const val DAYS = 2L

    /**
     * The days to rebuild for a restaurant in [zone] at [now], oldest first: none before its
     * [NIGHT_ENDS], or once it's reconciled through its yesterday.
     */
    fun daysDue(
        zone: ZoneId,
        reconciledThrough: LocalDate?,
        now: Instant,
    ): List<LocalDate> {
        val local = now.atZone(zone)
        val yesterday = local.toLocalDate().minusDays(1)
        val done = reconciledThrough != null && !reconciledThrough.isBefore(yesterday)
        return if (local.toLocalTime().isBefore(NIGHT_ENDS) || done) emptyList() else (DAYS - 1 downTo 0).map { yesterday.minusDays(it) }
    }

    /** How many days the restaurant's figures are behind its yesterday; 0 when they're current. */
    fun lagDays(
        zone: ZoneId,
        reconciledThrough: LocalDate,
        now: Instant,
    ): Long = maxOf(0, ChronoUnit.DAYS.between(reconciledThrough, now.atZone(zone).toLocalDate().minusDays(1)))
}

/** What one run of the rebuild did (reports §5, §9). */
data class ReconcileRun(
    val restaurants: Int,
    val days: Int,
    val mismatches: Int,
    val failed: Int,
    /** The most days any restaurant is behind its yesterday (`reports_reconcile_lag_days`). */
    val lagDays: Long,
)

/**
 * The job `/internal/jobs/sales-reconcile` (reports §5): hourly on Pro, once a day on the free
 * Hobby account. It adds each restaurant's last two days up again from the closed bills and
 * corrects the daily tables where they differ.
 *
 * **In plain words:** every close adds itself to its day's running totals at once. This job checks
 * those totals against the bills once the restaurant's night is over. A difference means a bug, so
 * it is logged as a mismatch (an alert in production), and the totals are put right.
 *
 * A platform transaction finds the restaurants due. Each one is then rebuilt in its own
 * transaction under `pg_try_advisory_xact_lock`, so two runs never work on it together; a
 * restaurant that fails is logged and left for the next run.
 */
class SalesReconcile(
    private val transactor: Transactor,
    private val zones: RestaurantZones,
    private val reconciliations: ReconciliationRepository,
    private val sales: DailySalesRepository,
    private val source: SalesSource,
    private val clock: Clock,
) {
    suspend fun run(): ReconcileRun {
        val now = clock.instant()
        val due =
            transactor.platformTransaction("sales reconcile: restaurants due") {
                val done = reconciliations.all(this)
                zones.all(this).filter { ReconcileRules.daysDue(ZoneId.of(it.timeZone), done[it.id]?.reconciledThrough, now).isNotEmpty() }
            }
        var restaurants = 0
        var days = 0
        var mismatches = 0
        var failed = 0
        due.take(MAX_PER_RUN).forEach { restaurant ->
            try {
                rebuild(restaurant.id, ZoneId.of(restaurant.timeZone), now)?.let { (rebuilt, mismatched) ->
                    restaurants++
                    days += rebuilt
                    mismatches += mismatched
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                failed++
                // Only the type: a message could carry row values (CLAUDE.md, SQL errors).
                log.error("sales reconcile failed: restaurant_id={} error={}", restaurant.id, e::class.qualifiedName)
            }
        }
        val lag =
            transactor.platformTransaction("sales reconcile: lag") {
                val done = reconciliations.all(this)
                zones
                    .all(this)
                    .mapNotNull { z -> done[z.id]?.let { ReconcileRules.lagDays(ZoneId.of(z.timeZone), it.reconciledThrough, now) } }
                    .maxOrNull() ?: 0
            }
        // NFR-OBS-01 until metrics go out over OTLP (ADR-0009): alert on any mismatch, and on a lag above 2 days on Pro.
        log.info(
            "sales reconcile: restaurants={} days={} mismatches={} failed={} lag_days={}",
            restaurants,
            days,
            mismatches,
            failed,
            lag,
        )
        return ReconcileRun(restaurants, days, mismatches, failed, lag)
    }

    /** Rebuilds the restaurant's due days: (days, mismatches), or `null` if another run has it or it's no longer due. */
    private suspend fun rebuild(
        restaurantId: RestaurantId,
        zone: ZoneId,
        now: Instant,
    ): Pair<Int, Int>? =
        transactor.inTransaction(restaurantId) {
            if (!tryAdvisoryLock(this, "job:sales-reconcile:$restaurantId")) return@inTransaction null
            val days = ReconcileRules.daysDue(zone, reconciliations.lock(this, restaurantId)?.reconciledThrough, now)
            if (days.isEmpty()) return@inTransaction null
            val mismatched =
                days.filter { date ->
                    val stored = sales.day(this, restaurantId, date)
                    val computed = tally(source.facts(this, restaurantId, date))
                    if (computed.sameFiguresAs(stored)) return@filter false
                    // reports_reconcile_mismatch_total: the restaurant and the day only, never figures.
                    log.warn("sales reconcile mismatch: restaurant_id={} date={}", restaurantId, date)
                    sales.replaceDay(this, restaurantId, date, computed.keepingNamesOf(stored))
                    true
                }
            reconciliations.save(this, restaurantId, days.last(), now, mismatched.maxOrNull())
            days.size to mismatched.size
        }

    private companion object {
        private val log = LoggerFactory.getLogger(SalesReconcile::class.java)

        /** S-5: ≈ 50–60 ms per restaurant-day, so ≈ 2 min for 1,000 restaurants × 2 days, within the function's limit. */
        const val MAX_PER_RUN = 1_000
    }
}
