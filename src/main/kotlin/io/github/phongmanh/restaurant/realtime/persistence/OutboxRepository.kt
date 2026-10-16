package io.github.phongmanh.restaurant.realtime.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.instant
import io.github.phongmanh.restaurant.core.persistence.list
import java.time.Instant
import java.util.UUID

/** The unsent rows: how many, and when the oldest was written (realtime §11). */
data class PendingOutbox(
    val count: Int,
    val oldestCreatedAt: Instant?,
)

/**
 * `outbox` (realtime §3, §4.3). A request works on its own restaurant's rows in a tenant
 * transaction; the sweep job on every restaurant's in a platform transaction ([restaurantId] `null`).
 */
class OutboxRepository {
    /**
     * Claims up to [limit] unsent rows due by [dueBy], oldest due first, and returns their events in
     * `seq` order per restaurant. A claimed row's next try moves out by 10 s × 2^attempts, at most
     * 5 min, so a claimer that dies leaves its rows to come back. `SKIP LOCKED`: two claimers never
     * take the same row. The claim commits with its transaction, before anything is sent.
     */
    fun claim(
        tx: TxScope,
        restaurantId: RestaurantId?,
        dueBy: Instant,
        now: Instant,
        limit: Int,
    ): List<LoggedEvent> {
        val ofRestaurant = if (restaurantId == null) "" else "AND restaurant_id = ? "
        val params = listOfNotNull(now, dueBy, restaurantId?.value) + limit
        // least(attempts, 5): 10 s × 2^5 is past the 5 min cap already, and 2^attempts would overflow
        // a double for a row that keeps failing until the purge (thousands of attempts in 7 days).
        val sql =
            """
            WITH claimed AS (
                UPDATE outbox o
                SET attempts = least(o.attempts + 1, 32767),
                    next_attempt_at = ?::timestamptz + least(interval '5 minutes', interval '10 seconds' * power(2, least(o.attempts, 5)))
                WHERE o.event_id IN (
                    SELECT event_id FROM outbox
                    WHERE published_at IS NULL AND next_attempt_at <= ? $ofRestaurant
                    ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING o.event_id)
            SELECT ${EventRepository.COLUMNS} FROM domain_events e JOIN claimed c ON c.event_id = e.id
            ORDER BY e.restaurant_id, e.seq
            """.trimIndent()
        return ExposedTransactor.connection(tx).prepareStatement(sql).use { it.bind(params).list(EventRepository::event) }
    }

    /** Marks the rows of [eventIds] sent at [at]. A row sent already keeps its first time. */
    fun markSent(
        tx: TxScope,
        eventIds: List<UUID>,
        at: Instant,
    ) {
        if (eventIds.isEmpty()) return
        ExposedTransactor
            .connection(tx)
            .prepareStatement("UPDATE outbox SET published_at = ? WHERE event_id = ANY(?::uuid[]) AND published_at IS NULL")
            .use { it.bind(at, eventIds.map(UUID::toString)).executeUpdate() }
    }

    /** Every restaurant's unsent rows (the sweep, platform transaction). */
    fun pending(tx: TxScope): PendingOutbox =
        ExposedTransactor
            .connection(tx)
            .prepareStatement("SELECT count(*), min(created_at) FROM outbox WHERE published_at IS NULL")
            .use { it.firstOrNull { rs -> PendingOutbox(rs.getInt(1), rs.instant("min")) } } ?: PendingOutbox(0, null)
}
