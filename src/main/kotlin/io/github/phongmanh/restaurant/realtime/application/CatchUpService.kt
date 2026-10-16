package io.github.phongmanh.restaurant.realtime.application

import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.realtime.domain.Viewer
import io.github.phongmanh.restaurant.realtime.persistence.EventRange
import io.github.phongmanh.restaurant.realtime.persistence.EventRepository
import io.github.phongmanh.restaurant.realtime.persistence.LoggedEvent
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/**
 * The `Snapshots` port (realtime §6.3): the current state of a batch of one aggregate type, as the
 * viewer would read it from the owning feature's own endpoint, loaded in one query. `orders`
 * answers for `order`, `tables` for `waiter_call`, so `realtime` never reads their tables.
 */
interface Snapshots {
    /** The aggregate type it loads, as events name it (`order`, `waiter_call`). */
    val aggregateType: String

    /**
     * The aggregates of [ids] that exist and that [viewer] may read, each once, in the body its
     * feature sends. A guest gets only their session's.
     */
    fun load(
        tx: TxScope,
        restaurantId: RestaurantId,
        ids: List<UUID>,
        viewer: Viewer,
    ): List<JsonElement>
}

/** What a client asked for: the cursor it holds (`null` for "where are we now?") and a page size. */
data class CatchUpRequest(
    val after: Long?,
    val limit: Int,
)

/**
 * One answer of the catch-up (realtime §6.1): the events, the current state of each order and
 * waiter call they name ([snapshots], by aggregate type), and the cursor to send back. [reset]
 * means the cursor was too old or ahead: the client reloads its screen.
 */
data class CatchUpPage(
    val events: List<LoggedEvent>,
    val snapshots: Map<String, List<JsonElement>>,
    val cursor: Long,
    val hasMore: Boolean,
    val reset: Boolean,
)

/**
 * The catch-up reads (realtime §6): what happened after the client's cursor. Guests read their
 * session's events; staff of every role read the restaurant's. 3–4 queries: the counter, the
 * events, the oldest kept event, and one batch per aggregate type that appears.
 */
class CatchUpService(
    private val transactor: Transactor,
    private val events: EventRepository,
    private val snapshots: List<Snapshots>,
) {
    suspend fun guest(
        guest: Caller.Guest,
        request: CatchUpRequest,
    ): CatchUpPage = read(guest.restaurantId, Viewer.Guest(guest.sessionId), request)

    suspend fun staff(
        staff: Caller.Staff,
        request: CatchUpRequest,
    ): CatchUpPage = read(staff.restaurantId, Viewer.Staff, request)

    private suspend fun read(
        restaurantId: RestaurantId,
        viewer: Viewer,
        request: CatchUpRequest,
    ): CatchUpPage =
        transactor.inTransaction(restaurantId) {
            // The counter first: every event up to it is committed, so the page has no holes (§2).
            val latest = events.latestSeq(this, restaurantId)
            val after = request.after ?: return@inTransaction empty(latest)
            if (after > latest) return@inTransaction empty(latest, reset = true)
            val rows = events.page(this, restaurantId, viewer, EventRange(after, latest, request.limit))
            // The oldest kept event is read after the page, so a purge that ran in between is seen:
            // a page with a hole in it is answered with a reset instead.
            val purgedUpTo = events.oldestSeq(this, restaurantId)?.minus(1) ?: latest
            if (after < purgedUpTo) return@inTransaction empty(latest, reset = true)
            val page = rows.take(request.limit)
            val hasMore = rows.size > request.limit
            CatchUpPage(
                events = page,
                snapshots = snapshotsOf(this, restaurantId, page, viewer),
                // A guest's page skips other sessions' events, so a full scan ends at the latest.
                cursor = if (hasMore) page.last().seq else latest,
                hasMore = hasMore,
                reset = false,
            )
        }

    private fun snapshotsOf(
        tx: TxScope,
        restaurantId: RestaurantId,
        page: List<LoggedEvent>,
        viewer: Viewer,
    ): Map<String, List<JsonElement>> {
        val ids = page.groupBy({ it.subject.type }, { it.subject.id }).mapValues { (_, v) -> v.distinct() }
        return snapshots.associate { source ->
            source.aggregateType to ids[source.aggregateType]?.let { source.load(tx, restaurantId, it, viewer) }.orEmpty()
        }
    }

    private fun empty(
        latest: Long,
        reset: Boolean = false,
    ) = CatchUpPage(emptyList(), emptyMap(), latest, hasMore = false, reset = reset)
}
