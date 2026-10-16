package io.github.phongmanh.restaurant.realtime.application

import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.persistence.tryAdvisoryLock
import io.github.phongmanh.restaurant.realtime.domain.RealtimeRules
import io.github.phongmanh.restaurant.realtime.persistence.EventRepository
import io.github.phongmanh.restaurant.realtime.persistence.OutboxRepository
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import kotlin.time.toJavaDuration

/** What one run of the sweep did, and what it left (realtime §11). */
data class SweepRun(
    val published: Int,
    val failed: Int,
    val pending: Int,
    val oldestPendingSeconds: Long?,
)

/**
 * The job `/internal/jobs/outbox-sweep` (realtime §4.3 way 2, §10): every minute on Pro, once a
 * day on the free Hobby account. It publishes the rows no request did, for restaurants that went
 * quiet.
 *
 * Each batch is claimed in a platform transaction that holds the job's lock
 * (`pg_try_advisory_xact_lock`) and commits before anything is sent; then the batch is published
 * and marked sent. A run stops at its first failure (the rest come back after their backoff), when
 * another run holds the lock, or after [MAX_BATCHES]. It ignores the circuit breaker: it is the
 * probe that finds Ably back.
 */
class OutboxSweep(
    private val transactor: Transactor,
    private val outbox: OutboxRepository,
    private val publisher: OutboxPublisher,
    private val clock: Clock,
) {
    suspend fun run(): SweepRun {
        var published = 0
        var failed = 0
        var batches = 0
        var more = true
        while (more && batches++ < MAX_BATCHES) {
            val now = clock.instant()
            val claimed =
                transactor.platformTransaction("outbox sweep: claim") {
                    if (tryAdvisoryLock(this, LOCK)) outbox.claim(this, null, now, now, BATCH) else emptyList()
                }
            // In seq order per restaurant (clients don't depend on it: they read the log, §4.3).
            val sent = publisher.publishInOrder(claimed, PublishPath.SWEEP)
            if (sent.isNotEmpty()) {
                transactor.platformTransaction(
                    "outbox sweep: mark sent",
                ) { outbox.markSent(this, sent, clock.instant()) }
            }
            published += sent.size
            failed += claimed.size - sent.size
            // Only a full batch, sent in full, may leave more to do.
            more = claimed.size == BATCH && sent.size == claimed.size
        }
        val pending = transactor.platformTransaction("outbox sweep: pending") { outbox.pending(this) }
        val oldest = pending.oldestCreatedAt?.let { Duration.between(it, clock.instant()).seconds }
        // NFR-OBS-01 until metrics go out over OTLP (ADR-0009): alert when the oldest unsent row is over 2 min old.
        log.info("outbox sweep: published={} failed={} pending={} oldest_pending_seconds={}", published, failed, pending.count, oldest)
        return SweepRun(published, failed, pending.count, oldest)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(OutboxSweep::class.java)
        const val LOCK = "job:outbox-sweep"
        const val BATCH = 100
        const val MAX_BATCHES = 20
    }
}

/**
 * The job `/internal/jobs/event-purge` (realtime §3, §10): hourly on Pro, daily on Hobby. Deletes
 * events older than 7 days in batches of 5,000; their outbox rows go with them. A client whose
 * cursor is older then gets `reset` (§6.1).
 */
class EventPurge(
    private val transactor: Transactor,
    private val events: EventRepository,
    private val clock: Clock,
) {
    suspend fun run(): Int {
        val before = clock.instant().minus(RealtimeRules.EVENT_RETENTION.toJavaDuration())
        var deleted = 0
        var batches = 0
        var more = true
        while (more && batches++ < MAX_BATCHES) {
            val n =
                transactor.platformTransaction("event purge") {
                    if (tryAdvisoryLock(this, LOCK)) events.purge(this, before, BATCH) else 0
                }
            deleted += n
            more = n == BATCH
        }
        return deleted
    }

    private companion object {
        const val LOCK = "job:event-purge"
        const val BATCH = 5_000
        const val MAX_BATCHES = 20
    }
}
