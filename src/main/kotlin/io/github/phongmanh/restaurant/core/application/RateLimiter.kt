package io.github.phongmanh.restaurant.core.application

import io.github.phongmanh.restaurant.core.domain.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.time.Duration

/** One limit (core-and-auth §11): at most [limit] hits per [window] for one subject. */
data class RateLimitPolicy(
    /** e.g. `auth.sign_in`; part of the store key. */
    val name: String,
    val limit: Long,
    val window: Duration,
)

/**
 * The `RateLimiter` port (ADR-0008): one fixed-window counter per key, shared by every instance.
 * [hit] counts one hit on [key], which already names the window, and returns the count so far.
 * It throws when the store can't answer; [RateLimits] then fails open.
 */
fun interface RateLimiter {
    suspend fun hit(
        key: String,
        window: Duration,
    ): Long
}

/**
 * Applies the limits (§11). Past a limit → `429 rate_limited` with `Retry-After` set to the seconds
 * left in the window. If the store fails or takes longer than [timeout], the request is served
 * (fail open): the WAF still guards the edge, and the per-account lock lives in PostgreSQL.
 */
class RateLimits(
    private val limiter: RateLimiter,
    private val clock: Clock,
    private val timeout: Duration,
) {
    suspend fun check(
        policy: RateLimitPolicy,
        subject: String,
    ) {
        val windowSeconds = policy.window.inWholeSeconds
        val now = clock.instant().epochSecond
        val windowStart = now - Math.floorMod(now, windowSeconds)
        val key = "rl:${policy.name}:$subject:$windowStart"
        val count =
            try {
                withTimeoutOrNull(timeout) { limiter.hit(key, policy.window) }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // The key holds the subject (an IP or a staff ID), so only the policy is logged.
                log.warn("rate limit store failed, served without a limit: {} ({})", policy.name, e::class.simpleName)
                return
            }
        if (count == null) {
            log.warn("rate limit store took over {}, served without a limit: {}", timeout, policy.name)
            return
        }
        if (count > policy.limit) throw DomainError.RateLimited(retryAfterSeconds = windowStart + windowSeconds - now)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(RateLimits::class.java)
    }
}
