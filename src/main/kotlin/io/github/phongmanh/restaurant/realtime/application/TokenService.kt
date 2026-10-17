package io.github.phongmanh.restaurant.realtime.application

import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.RateLimits
import io.github.phongmanh.restaurant.core.auth.RateLimitSettings
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.realtime.domain.Channels
import io.github.phongmanh.restaurant.realtime.domain.RealtimeRules
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration

/**
 * An Ably TokenRequest (realtime §8), signed locally with the API key: the client's Ably SDK trades
 * it for a token. Its fields keep Ably's own names, as the SDK reads them.
 */
data class SubscriptionTokenRequest(
    val keyName: String,
    val ttlMillis: Long,
    val capability: String,
    val timestamp: Long,
    val nonce: String,
    val mac: String,
)

/**
 * The `TokenIssuer` port (realtime §12): a token request that may only subscribe to [channel], for
 * [ttl], made at [at], with no `clientId` and no network call.
 */
fun interface TokenIssuer {
    fun issue(
        channel: String,
        ttl: Duration,
        at: Instant,
    ): SubscriptionTokenRequest
}

/** The rate-limit policies of this feature (realtime §9). */
object RealtimeLimits {
    /** Subscription tokens per guest session and per staff member. */
    const val TOKEN_SESSION = "realtime.token_session"
    const val TOKEN_STAFF = "realtime.token_staff"

    /** Catch-up reads per guest session and per staff member. */
    const val EVENTS_SESSION = "realtime.events_session"
    const val EVENTS_STAFF = "realtime.events_staff"
}

/** Applies [RealtimeLimits]. Over a limit → `429` with `Retry-After`; the client waits that long (§9). */
class RealtimeRateLimits(
    private val limits: RateLimits,
    private val settings: RateLimitSettings,
) {
    suspend fun token(caller: Caller) =
        when (caller) {
            is Caller.Guest -> limits.check(settings.policy(RealtimeLimits.TOKEN_SESSION), caller.sessionId.toString())
            is Caller.Staff -> limits.check(settings.policy(RealtimeLimits.TOKEN_STAFF), caller.staffId.toString())
            is Caller.Admin -> throw DomainError.Forbidden()
        }

    suspend fun events(caller: Caller) =
        when (caller) {
            is Caller.Guest -> limits.check(settings.policy(RealtimeLimits.EVENTS_SESSION), caller.sessionId.toString())
            is Caller.Staff -> limits.check(settings.policy(RealtimeLimits.EVENTS_STAFF), caller.staffId.toString())
            is Caller.Admin -> throw DomainError.Forbidden()
        }
}

/**
 * Subscription tokens (realtime §8): a guest may listen on their session's channel for 30 min, a
 * staff member of any role on their restaurant's staff channel for 60 min. The channel comes from
 * the caller, never from the request. Without an Ably key (dev only), `503`.
 */
class TokenService(
    private val issuer: TokenIssuer?,
    private val clock: Clock,
) {
    fun guest(guest: Caller.Guest): SubscriptionTokenRequest = issue(Channels.session(guest.sessionId), RealtimeRules.GUEST_TOKEN_TTL)

    fun staff(staff: Caller.Staff): SubscriptionTokenRequest = issue(Channels.staff(staff.restaurantId), RealtimeRules.STAFF_TOKEN_TTL)

    private fun issue(
        channel: String,
        ttl: Duration,
    ): SubscriptionTokenRequest = (issuer ?: throw DomainError.ServiceUnavailable()).issue(channel, ttl, clock.instant())
}
