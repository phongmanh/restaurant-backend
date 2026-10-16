package io.github.phongmanh.restaurant.realtime.http

import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.FieldError
import io.github.phongmanh.restaurant.core.domain.Rfc3339
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.caller
import io.github.phongmanh.restaurant.core.http.endpoint
import io.github.phongmanh.restaurant.core.http.job
import io.github.phongmanh.restaurant.core.http.staffCaller
import io.github.phongmanh.restaurant.realtime.application.CatchUpPage
import io.github.phongmanh.restaurant.realtime.application.CatchUpRequest
import io.github.phongmanh.restaurant.realtime.application.CatchUpService
import io.github.phongmanh.restaurant.realtime.application.EventPurge
import io.github.phongmanh.restaurant.realtime.application.OutboxSweep
import io.github.phongmanh.restaurant.realtime.application.RealtimeRateLimits
import io.github.phongmanh.restaurant.realtime.application.SubscriptionTokenRequest
import io.github.phongmanh.restaurant.realtime.application.TokenService
import io.github.phongmanh.restaurant.realtime.domain.RealtimeRules
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AggregateBody(
    val type: String,
    val id: String,
    val version: Int? = null,
)

@Serializable
data class DomainEventBody(
    val seq: Long,
    val type: String,
    val aggregate: AggregateBody,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("occurred_at") val occurredAt: String,
)

/** `GuestEventsPage` and `StaffEventsPage` (realtime §6.1). The snapshots are the features' own bodies. */
@Serializable
data class EventsPageBody(
    val events: List<DomainEventBody>,
    val orders: List<JsonElement>,
    @SerialName("waiter_calls") val waiterCalls: List<JsonElement>,
    val cursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
    val reset: Boolean? = null,
) {
    companion object {
        fun of(page: CatchUpPage) =
            EventsPageBody(
                events =
                    page.events.map {
                        DomainEventBody(
                            seq = it.seq,
                            type = it.type,
                            aggregate = AggregateBody(it.subject.type, it.subject.id.toString(), it.subject.version),
                            sessionId = it.sessionId?.toString(),
                            occurredAt = Rfc3339.format(it.occurredAt),
                        )
                    },
                orders = page.snapshots[ORDER].orEmpty(),
                waiterCalls = page.snapshots[WAITER_CALL].orEmpty(),
                cursor = page.cursor,
                hasMore = page.hasMore,
                reset = page.reset.takeIf { it },
            )

        private const val ORDER = "order"
        private const val WAITER_CALL = "waiter_call"
    }
}

/**
 * `RealtimeTokenRequest`: Ably's TokenRequest with Ably's own camelCase field names, the one
 * exception to `snake_case` (realtime §8), because the client SDK reads it as it is.
 */
@Serializable
data class RealtimeTokenBody(
    val keyName: String,
    val ttl: Long,
    val capability: String,
    val timestamp: Long,
    val nonce: String,
    val mac: String,
) {
    companion object {
        fun of(t: SubscriptionTokenRequest) = RealtimeTokenBody(t.keyName, t.ttlMillis, t.capability, t.timestamp, t.nonce, t.mac)
    }
}

/**
 * The catch-up and token routes (realtime §6.4). Guests read their session's events, staff of every
 * role their restaurant's; the channel and the session come from the caller. Each route applies its
 * rate limit, then reads the query.
 */
class RealtimeRoutes(
    private val catchUp: CatchUpService,
    private val tokens: TokenService,
    private val limits: RealtimeRateLimits,
) {
    fun install(route: Route) {
        val e = Endpoints.Realtime
        route.endpoint(e.GUEST_CATCH_UP) {
            val guest = call.caller() as Caller.Guest
            limits.events(guest)
            call.respond(EventsPageBody.of(catchUp.guest(guest, call.catchUpRequest())))
        }
        route.endpoint(e.STAFF_CATCH_UP) {
            val staff = call.staffCaller()
            limits.events(staff)
            call.respond(EventsPageBody.of(catchUp.staff(staff, call.catchUpRequest())))
        }
        route.endpoint(e.GUEST_REALTIME_TOKEN) {
            val guest = call.caller() as Caller.Guest
            limits.token(guest)
            call.respond(RealtimeTokenBody.of(tokens.guest(guest)))
        }
        route.endpoint(e.STAFF_REALTIME_TOKEN) {
            val staff = call.staffCaller()
            limits.token(staff)
            call.respond(RealtimeTokenBody.of(tokens.staff(staff)))
        }
    }
}

/** Reads `after` (0 or more; absent = "where are we now?") and `limit` (1–200, default 100). Invalid → `400`. */
private fun ApplicationCall.catchUpRequest(): CatchUpRequest {
    val errors = mutableListOf<FieldError>()
    val afterParam = request.queryParameters["after"]
    val after = afterParam?.toLongOrNull()?.takeIf { it >= 0 }
    if (afterParam != null && after == null) errors += FieldError("query.after", "invalid", "the cursor from the last answer")
    val limitParam = request.queryParameters["limit"]
    val limit = if (limitParam == null) RealtimeRules.DEFAULT_PAGE else limitParam.toIntOrNull()?.takeIf { it in 1..RealtimeRules.MAX_PAGE }
    if (limit == null) errors += FieldError("query.limit", "out_of_range", "an integer from 1 to ${RealtimeRules.MAX_PAGE}")
    if (errors.isNotEmpty()) throw DomainError.ValidationFailed(errors)
    return CatchUpRequest(after, limit!!)
}

/** What a sweep run answers Vercel Cron. Not part of the contract. */
@Serializable
data class SweepResult(
    val published: Int,
    val failed: Int,
    val pending: Int,
    @SerialName("oldest_pending_seconds") val oldestPendingSeconds: Long? = null,
)

/** What a purge run answers Vercel Cron. Not part of the contract. */
@Serializable
data class PurgeResult(
    val deleted: Int,
)

/** The realtime jobs (realtime §10), behind the cron secret. */
class RealtimeJobRoutes(
    private val sweep: OutboxSweep,
    private val purge: EventPurge,
) {
    fun install(route: Route) {
        route.job(Endpoints.Jobs.OUTBOX_SWEEP) {
            val run = sweep.run()
            call.respond(SweepResult(run.published, run.failed, run.pending, run.oldestPendingSeconds))
        }
        route.job(Endpoints.Jobs.EVENT_PURGE) {
            call.respond(PurgeResult(purge.run()))
        }
    }
}
