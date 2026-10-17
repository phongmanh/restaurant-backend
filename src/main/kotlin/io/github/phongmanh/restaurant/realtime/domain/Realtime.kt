package io.github.phongmanh.restaurant.realtime.domain

import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The Ably channels (realtime §4.2, §8): one per restaurant's staff, one per table session. A
 * subscription token names exactly one of them.
 */
object Channels {
    fun staff(restaurantId: RestaurantId) = "restaurant.$restaurantId.staff"

    fun session(sessionId: TableSessionId) = "session.$sessionId"
}

/** Who reads the event log: a guest sees their session's events only; staff see the restaurant's (realtime §6). */
sealed interface Viewer {
    data class Guest(
        val sessionId: TableSessionId,
    ) : Viewer

    data object Staff : Viewer
}

/** The times and sizes of the realtime design. */
object RealtimeRules {
    /** Events and sent outbox rows are kept this long (realtime §3, architecture §6). */
    val EVENT_RETENTION: Duration = 7.days

    /** `limit` of a catch-up (realtime §6.3). */
    const val DEFAULT_PAGE = 100
    const val MAX_PAGE = 200

    /** Inline publishing (§4.2, §14 Q2): one try capped at 300 ms; on Ably's rate limit, one more after 50 ms. */
    val PUBLISH_TIMEOUT: Duration = 300.milliseconds
    val RATE_LIMIT_RETRY_AFTER: Duration = 50.milliseconds

    /** A request also publishes up to 20 of its restaurant's rows that are 10 s overdue (§4.3). */
    val OVERDUE_AFTER: Duration = 10.seconds
    const val OVERDUE_PER_REQUEST = 20

    /** The per-instance circuit breaker (§4.2, architecture §10): 5 failures in a row skip inline publishing for 30 s. */
    const val BREAKER_FAILURES = 5
    val BREAKER_OPEN_FOR: Duration = 30.seconds

    /** Subscription token lifetimes (§8, §14 Q3). */
    val GUEST_TOKEN_TTL: Duration = 30.minutes
    val STAFF_TOKEN_TTL: Duration = 60.minutes
}
