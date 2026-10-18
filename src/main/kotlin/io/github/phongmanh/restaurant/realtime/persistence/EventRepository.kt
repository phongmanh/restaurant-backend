package io.github.phongmanh.restaurant.realtime.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.instant
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.core.persistence.uuid
import io.github.phongmanh.restaurant.realtime.application.EventAudience
import io.github.phongmanh.restaurant.realtime.application.EventSubject
import io.github.phongmanh.restaurant.realtime.domain.Viewer
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** One row of `domain_events`, as written or read back. */
data class LoggedEvent(
    val id: UUID,
    val restaurantId: RestaurantId,
    val seq: Long,
    val occurredAt: Instant,
    val type: String,
    val subject: EventSubject,
    val audience: EventAudience,
    val sessionId: TableSessionId?,
)

/** One page of the log for a viewer: events after a cursor, at most up to the restaurant's latest. */
data class EventRange(
    val after: Long,
    val upTo: Long,
    val limit: Int,
)

/**
 * `event_counters`, `domain_events` and `outbox` (realtime §3). Every method a request calls takes
 * the restaurant; [purge] is the platform job's, across restaurants.
 */
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

    /** The event and, with [outbox], its outbox row, due now (none while live push is off, CR-013). */
    fun insert(
        tx: TxScope,
        event: LoggedEvent,
        outbox: Boolean = true,
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
        if (!outbox) return
        conn
            .prepareStatement("INSERT INTO outbox (event_id, restaurant_id, created_at, next_attempt_at) VALUES (?, ?, ?, ?)")
            .use { it.bind(event.id, event.restaurantId.value, event.occurredAt, event.occurredAt).executeUpdate() }
    }

    /**
     * The restaurant's latest committed `seq`, or 0 before its first event. Every event up to it is
     * committed: each one held the counter row until its commit (realtime §2).
     */
    fun latestSeq(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): Long =
        ExposedTransactor
            .connection(tx)
            .prepareStatement("SELECT last_seq FROM event_counters WHERE restaurant_id = ?")
            .use { it.bind(restaurantId.value).firstOrNull { rs -> rs.getLong(1) } } ?: 0

    /** The oldest `seq` the restaurant still keeps, or `null` when the purge has taken all of them. */
    fun oldestSeq(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): Long? =
        ExposedTransactor
            .connection(tx)
            .prepareStatement("SELECT min(seq) FROM domain_events WHERE restaurant_id = ?")
            .use { it.bind(restaurantId.value).firstOrNull { rs -> rs.getLong(1).takeUnless { rs.wasNull() } } }

    /**
     * The events [viewer] may see in [range], oldest first, one more than the limit so the caller
     * knows there's a next page. A guest sees only events addressed to their session (realtime §5).
     */
    fun page(
        tx: TxScope,
        restaurantId: RestaurantId,
        viewer: Viewer,
        range: EventRange,
    ): List<LoggedEvent> {
        val guest = viewer as? Viewer.Guest
        val filter = if (guest == null) "" else "AND session_id = ? AND audience = 'staff_and_session' "
        val params = listOfNotNull(restaurantId.value, range.after, range.upTo, guest?.sessionId?.value) + (range.limit + 1)
        return ExposedTransactor
            .connection(tx)
            .prepareStatement("$SELECT WHERE restaurant_id = ? AND seq > ? AND seq <= ? $filter ORDER BY seq LIMIT ?")
            .use { it.bind(params).list(::event) }
    }

    /**
     * Deletes up to [limit] events that occurred before [before], across restaurants (the purge job,
     * realtime §10; platform transaction). Their outbox rows go with them.
     */
    fun purge(
        tx: TxScope,
        before: Instant,
        limit: Int,
    ): Int =
        ExposedTransactor
            .connection(tx)
            .prepareStatement(
                "DELETE FROM domain_events WHERE ctid = ANY(ARRAY(SELECT ctid FROM domain_events WHERE occurred_at < ? LIMIT ?))",
            ).use { it.bind(before, limit).executeUpdate() }

    internal companion object {
        const val COLUMNS =
            "restaurant_id, seq, id, occurred_at, type, aggregate_type, aggregate_id, aggregate_version, session_id, audience"
        const val SELECT = "SELECT $COLUMNS FROM domain_events"

        fun event(rs: ResultSet) =
            LoggedEvent(
                id = rs.uuid("id")!!,
                restaurantId = RestaurantId(rs.uuid("restaurant_id")!!),
                seq = rs.getLong("seq"),
                occurredAt = rs.instant("occurred_at")!!,
                type = rs.getString("type"),
                subject =
                    EventSubject(
                        rs.getString("aggregate_type"),
                        rs.uuid("aggregate_id")!!,
                        rs.getInt("aggregate_version").takeUnless { rs.wasNull() },
                    ),
                audience = EventAudience.entries.first { it.value == rs.getString("audience") },
                sessionId = rs.uuid("session_id")?.let(::TableSessionId),
            )
    }
}
