package io.github.phongmanh.restaurant.realtime.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.realtime.application.EventAudience
import io.github.phongmanh.restaurant.realtime.application.EventSubject
import java.time.Instant
import java.util.UUID

/** One row of `domain_events`, about to be written. */
data class NewEvent(
    val id: UUID,
    val restaurantId: RestaurantId,
    val seq: Long,
    val occurredAt: Instant,
    val type: String,
    val subject: EventSubject,
    val audience: EventAudience,
    val sessionId: TableSessionId?,
)

/** `event_counters`, `domain_events` and `outbox` (realtime §3). Every method takes the restaurant. */
class EventRepository {
    /**
     * The restaurant's next sequence number, in one statement that also creates the counter on the
     * first event (realtime §3). The row stays locked until the transaction ends.
     */
    fun nextSeq(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): Long =
        ExposedTransactor
            .connection(tx)
            .prepareStatement(
                "INSERT INTO event_counters (restaurant_id, last_seq) VALUES (?, 1) " +
                    "ON CONFLICT (restaurant_id) DO UPDATE SET last_seq = event_counters.last_seq + 1 RETURNING last_seq",
            ).use { it.bind(restaurantId.value).firstOrNull { rs -> rs.getLong(1) } } ?: error("event counter not visible")

    /** The event and its outbox row, due now. */
    fun insert(
        tx: TxScope,
        event: NewEvent,
    ) {
        val conn = ExposedTransactor.connection(tx)
        conn
            .prepareStatement(
                """
                INSERT INTO domain_events (restaurant_id, seq, id, occurred_at, type, aggregate_type, aggregate_id,
                    aggregate_version, session_id, audience)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use {
                it
                    .bind(
                        event.restaurantId.value,
                        event.seq,
                        event.id,
                        event.occurredAt,
                        event.type,
                        event.subject.type,
                        event.subject.id,
                        event.subject.version,
                        event.sessionId?.value,
                        event.audience.value,
                    ).executeUpdate()
            }
        conn
            .prepareStatement("INSERT INTO outbox (event_id, restaurant_id, created_at, next_attempt_at) VALUES (?, ?, ?, ?)")
            .use { it.bind(event.id, event.restaurantId.value, event.occurredAt, event.occurredAt).executeUpdate() }
    }
}
