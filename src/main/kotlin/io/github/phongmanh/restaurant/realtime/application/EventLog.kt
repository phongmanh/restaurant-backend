package io.github.phongmanh.restaurant.realtime.application

import io.github.phongmanh.restaurant.core.application.TxKey
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.IdGenerator
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.Rfc3339
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.realtime.persistence.EventRepository
import io.github.phongmanh.restaurant.realtime.persistence.LoggedEvent
import java.time.Clock
import java.util.UUID

/** Who is sent an event (realtime §5): the restaurant's staff, or its staff and the guests of one table session. */
enum class EventAudience(
    val value: String,
) {
    STAFF("staff"),
    STAFF_AND_SESSION("staff_and_session"),
}

/** What an event is about: its aggregate's type (`order`, `menu`, …), ID and version (realtime §3). */
data class EventSubject(
    val type: String,
    val id: UUID,
    val version: Int?,
)

/** An event as written: its ID (also the Ably message ID) and its place in the restaurant's sequence. */
data class AppendedEvent(
    val id: UUID,
    val seq: Long,
)

/**
 * The event log (realtime §4.1, ADR-0003): the one port every feature writes its events through.
 *
 * **In plain words:** a change and the note that it happened are saved together. Each event gets
 * the restaurant's next sequence number, and an outbox row that says "still to be sent". If the
 * change rolls back, so do both. Sending happens after the commit, never inside it.
 *
 * The transaction's events are published once it commits ([OutboxPublisher.afterCommit]), never
 * inside it (architecture §9).
 */
interface EventLog {
    /**
     * Appends an event in [tx], the transaction of the change. It takes the counter lock, held until
     * commit, so call it after the change's own rows (lock order, CLAUDE.md). [sessionId] is required
     * when [audience] includes the session's guests.
     */
    fun append(
        tx: TxScope,
        restaurantId: RestaurantId,
        type: String,
        subject: EventSubject,
        audience: EventAudience,
        sessionId: TableSessionId? = null,
    ): AppendedEvent
}

/**
 * [EventLog] on `event_counters`, `domain_events` and `outbox`. Without [push] (`LIVE_PUSH=off`,
 * release 1, CR-013) it writes no outbox row and publishes nothing: screens poll the log (realtime §7).
 */
class OutboxEventLog(
    private val events: EventRepository,
    private val publisher: OutboxPublisher,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val push: Boolean = true,
) : EventLog {
    override fun append(
        tx: TxScope,
        restaurantId: RestaurantId,
        type: String,
        subject: EventSubject,
        audience: EventAudience,
        sessionId: TableSessionId?,
    ): AppendedEvent {
        require(tx.restaurantId == restaurantId) { "an event is written in its own restaurant's transaction" }
        require(audience == EventAudience.STAFF || sessionId != null) { "a session's event names the session" }
        val seq = events.nextSeq(tx, restaurantId)
        val event = LoggedEvent(ids.next(), restaurantId, seq, Rfc3339.truncate(clock.instant()), type, subject, audience, sessionId)
        events.insert(tx, event, outbox = push)
        if (!push) return AppendedEvent(event.id, seq)
        // The first event of the transaction registers its publishing; the rest join the list.
        tx.getOrPut(WRITTEN) {
            mutableListOf<LoggedEvent>().also { written ->
                tx.afterCommit { publisher.afterCommit(restaurantId, written) }
            }
        } +=
            event
        return AppendedEvent(event.id, seq)
    }

    private companion object {
        val WRITTEN = TxKey<MutableList<LoggedEvent>>("realtime.written")
    }
}
