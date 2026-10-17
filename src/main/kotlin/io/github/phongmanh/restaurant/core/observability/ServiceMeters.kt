package io.github.phongmanh.restaurant.core.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * The domain meters of architecture §13: live-update publishing and its delay, the outbox, orders
 * and sessions, and the nightly sales reconcile. No meter is tagged with a restaurant, a session or
 * any other ID: IDs would multiply the series, and the logs already carry them.
 *
 * A gauge is the last value this instance saw. A job runs on whichever instance Vercel Cron reached,
 * so a dashboard takes the latest value across instances.
 */
class ServiceMeters(
    private val registry: MeterRegistry,
) {
    private val outboxPending = AtomicLong()
    private val outboxOldest = AtomicLong()
    private val reconcileLag = AtomicLong()

    private val feedDelay: Timer =
        Timer
            .builder("realtime.feed.delay")
            .description("From the change to Ably accepting its notification (NFR-PERF-03)")
            // NFR-PERF-03: p95 ≤ 2 s and p99 ≤ 5 s.
            .serviceLevelObjectives(Duration.ofSeconds(FEED_P95_S), Duration.ofSeconds(FEED_P99_S))
            .register(registry)

    init {
        registry.gauge("outbox.pending", outboxPending)
        registry.gauge("outbox.oldest.age.seconds", outboxOldest)
        registry.gauge("reports.reconcile.lag.days", reconcileLag)
    }

    /** One notification sent (`sent`) or refused (`failed`, `rate_limited`), by the path that sent it. */
    fun published(
        path: String,
        result: String,
    ) = counter("realtime.publish", "path", path, "result", result).increment()

    /** The time from the change to Ably accepting its notification. */
    fun feedDelay(delay: Duration) = feedDelay.record(delay.coerceAtLeast(Duration.ZERO))

    /** What the outbox sweep left unsent, and how old the oldest of it is (0 when none). */
    fun outbox(
        pending: Int,
        oldestSeconds: Long?,
    ) {
        outboxPending.set(pending.toLong())
        outboxOldest.set(oldestSeconds ?: 0)
    }

    /** An order committed, placed by a guest or by staff (FR-003, FR-014). */
    fun orderPlaced(byStaff: Boolean) = counter("orders.placed", "by", if (byStaff) "staff" else "guest").increment()

    /** A table session opened by a scan (FR-001); joining one doesn't count. */
    fun sessionOpened() = counter("sessions.opened").increment()

    /** One reconcile run: days rebuilt, mismatches found, restaurants that failed, and the worst lag. */
    fun reconcile(
        days: Int,
        mismatches: Int,
        failed: Int,
        lagDays: Long,
    ) {
        counter("reports.reconcile.days").increment(days.toDouble())
        counter("reports.reconcile.mismatches").increment(mismatches.toDouble())
        counter("reports.reconcile.failures").increment(failed.toDouble())
        reconcileLag.set(lagDays)
    }

    private fun counter(
        name: String,
        vararg tags: String,
    ): Counter = registry.counter(name, *tags)

    companion object {
        private const val FEED_P95_S = 2L
        private const val FEED_P99_S = 5L

        /** For code built outside the service, such as a unit test: counts in a registry nobody reads. */
        fun none() = ServiceMeters(SimpleMeterRegistry())
    }
}
