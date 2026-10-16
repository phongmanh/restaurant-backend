package io.github.phongmanh.restaurant.realtime.application

import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.realtime.domain.Channels
import io.github.phongmanh.restaurant.realtime.domain.RealtimeRules
import io.github.phongmanh.restaurant.realtime.persistence.LoggedEvent
import io.github.phongmanh.restaurant.realtime.persistence.OutboxRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.time.toJavaDuration

/**
 * One notification (realtime §4.2): sent to every channel in [channels] in one request. [id] is the
 * event's, so Ably drops a repeat of it (idempotent publishing). [data] names the change and nothing
 * personal: `seq`, `type`, `id`, `version` and, for a session's event, `session_id`.
 */
data class Notification(
    val id: UUID,
    val name: String,
    val channels: List<String>,
    val data: Map<String, Any>,
)

/** What a publish came to. [code] is Ably's error code, or a word for a failure without one (`timeout`). */
sealed interface PublishResult {
    data object Sent : PublishResult

    data class Failed(
        val code: String,
        val rateLimited: Boolean = false,
    ) : PublishResult
}

/**
 * The `RealtimePublisher` port (realtime §12): one REST request to the pub/sub service, capped at
 * [RealtimeRules.PUBLISH_TIMEOUT]. It never throws; a failure is a [PublishResult.Failed].
 */
fun interface RealtimePublisher {
    suspend fun publish(notification: Notification): PublishResult
}

/**
 * The per-instance circuit breaker (realtime §4.2, architecture §10): after
 * [RealtimeRules.BREAKER_FAILURES] failures in a row, requests skip publishing for
 * [RealtimeRules.BREAKER_OPEN_FOR]; their rows wait for a later publisher. Any success closes it.
 */
class CircuitBreaker(
    private val clock: Clock,
) {
    private var failures = 0
    private var openUntil: Instant = Instant.MIN

    @Synchronized
    fun isOpen(): Boolean = clock.instant().isBefore(openUntil)

    @Synchronized
    fun record(success: Boolean) {
        if (success) {
            failures = 0
            openUntil = Instant.MIN
        } else if (++failures >= RealtimeRules.BREAKER_FAILURES) {
            failures = 0
            openUntil = clock.instant().plus(RealtimeRules.BREAKER_OPEN_FOR.toJavaDuration())
        }
    }
}

/** Who published an event (realtime §11): the request that wrote it, a later request, or the sweep job. */
enum class PublishPath { INLINE, REQUEST, SWEEP }

/**
 * Sends logged events to Ably (realtime §4.2–4.3, ADR-0003).
 *
 * **In plain words:** after a change is saved, the request sends a tiny "something changed, you're
 * at 4,812" to the screens that care, then notes in the outbox that it did. If that fails, the
 * request still succeeds, and the note stays "unsent": a later request of the same restaurant, the
 * sweep job, or the screens' own polling (they read the log, never Ably) cover it.
 */
class OutboxPublisher(
    private val transactor: Transactor,
    private val outbox: OutboxRepository,
    private val realtime: RealtimePublisher,
    private val breaker: CircuitBreaker,
    private val clock: Clock,
) {
    /**
     * After [events]' transaction has committed, before the response (§14 Q2): publishes them, marks
     * the sent ones, and claims and publishes up to [RealtimeRules.OVERDUE_PER_REQUEST] of the
     * restaurant's rows that are [RealtimeRules.OVERDUE_AFTER] overdue (§4.3 way 1). Skipped while the
     * breaker is open.
     */
    suspend fun afterCommit(
        restaurantId: RestaurantId,
        events: List<LoggedEvent>,
    ) {
        if (breaker.isOpen()) return
        val sent = publishInOrder(events, PublishPath.INLINE)
        val now = clock.instant()
        val overdue =
            transactor.inTransaction(restaurantId) {
                outbox.markSent(this, sent, now)
                if (breaker.isOpen()) {
                    emptyList()
                } else {
                    val dueBy = now.minus(RealtimeRules.OVERDUE_AFTER.toJavaDuration())
                    outbox.claim(this, restaurantId, dueBy, now, RealtimeRules.OVERDUE_PER_REQUEST)
                }
            }
        if (overdue.isEmpty()) return
        val late = publishInOrder(overdue, PublishPath.REQUEST)
        if (late.isNotEmpty()) transactor.inTransaction(restaurantId) { outbox.markSent(this, late, clock.instant()) }
    }

    /**
     * Publishes claimed [events] in order and returns the IDs sent. Stops at the first failure, so a
     * service that is down isn't sent the rest; they come back after their backoff.
     */
    suspend fun publishInOrder(
        events: List<LoggedEvent>,
        path: PublishPath,
    ): List<UUID> {
        val sent = mutableListOf<UUID>()
        for (event in events) {
            if (!publish(event, path)) break
            sent += event.id
        }
        return sent
    }

    /** One event, with one retry after Ably's rate limit (`42918`, S-3 finding 2). Returns whether it was sent. */
    private suspend fun publish(
        event: LoggedEvent,
        path: PublishPath,
    ): Boolean {
        val notification = notificationOf(event)
        var result = send(notification)
        if (result is PublishResult.Failed && result.rateLimited) {
            delay(RealtimeRules.RATE_LIMIT_RETRY_AFTER)
            result = send(notification)
        }
        breaker.record(result == PublishResult.Sent)
        if (result is PublishResult.Failed) {
            // IDs and codes only: an event carries nothing personal (realtime §3).
            log.warn("realtime publish failed: path={} code={} restaurant={} seq={}", path, result.code, event.restaurantId, event.seq)
        }
        return result == PublishResult.Sent
    }

    private suspend fun send(notification: Notification): PublishResult =
        try {
            realtime.publish(notification)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            PublishResult.Failed(e::class.simpleName ?: "error")
        }

    private companion object {
        private val log = LoggerFactory.getLogger(OutboxPublisher::class.java)

        /** Both channels in one request when the session's guests see it (S-3 finding 1). */
        fun notificationOf(event: LoggedEvent): Notification {
            val session = event.sessionId.takeIf { event.audience == EventAudience.STAFF_AND_SESSION }
            val channels = listOfNotNull(Channels.staff(event.restaurantId), session?.let(Channels::session))
            val data =
                buildMap<String, Any> {
                    put("seq", event.seq)
                    put("type", event.type)
                    put("id", event.subject.id.toString())
                    event.subject.version?.let { put("version", it) }
                    session?.let { put("session_id", it.toString()) }
                }
            return Notification(event.id, event.type, channels, data)
        }
    }
}
