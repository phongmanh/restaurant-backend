package io.github.phongmanh.restaurant.restaurants.persistence

import io.github.phongmanh.restaurant.core.application.ActorType
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.instant
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.core.persistence.uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** One audit entry as the audit read shows it (restaurants §9). */
data class AuditEntry(
    val id: UUID,
    val occurredAt: Instant,
    val action: String,
    val actorType: ActorType,
    val actorId: UUID?,
    /** From `staff_accounts`: "Deleted user" once the account is anonymised. */
    val actorDisplayName: String?,
    val targetType: String?,
    val targetId: UUID?,
    val requestId: String?,
    val ip: String?,
    val userAgent: String?,
    val details: JsonObject,
)

/** What an audit read asks for (§9): entries from [from] to [to], both included. [action] is a prefix, e.g. `staff.`. */
data class AuditQuery(
    val from: Instant,
    val to: Instant,
    val action: String? = null,
    val actorId: UUID? = null,
    val targetId: UUID? = null,
    /** The last entry the client has (`occurred_at`, `id`), newest first. */
    val after: Pair<Instant, UUID>? = null,
    val limit: Int,
)

/** Reads of `audit_events` for the audit log screens and privacy exports. Writing is `core`'s AuditLog. */
class AuditQueryRepository {
    /**
     * One chain's entries, newest first: [restaurantId]'s, or the platform's when `null`. In a
     * restaurant's transaction row-level security already limits the rows to that restaurant; the
     * filter states it anyway.
     */
    fun page(
        tx: TxScope,
        restaurantId: RestaurantId?,
        query: AuditQuery,
    ): List<AuditEntry> {
        val conditions =
            mutableListOf(
                if (restaurantId ==
                    null
                ) {
                    "e.restaurant_id IS NULL"
                } else {
                    "e.restaurant_id = ?"
                },
                "e.occurred_at >= ?",
                "e.occurred_at <= ?",
            )
        val params = mutableListOf<Any?>()
        restaurantId?.let { params += it.value }
        params += query.from
        params += query.to
        query.action?.let {
            conditions += "e.action LIKE ? ESCAPE '\\'"
            params += it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        query.actorId?.let {
            conditions += "e.actor_id = ?"
            params += it
        }
        query.targetId?.let {
            conditions += "e.target_id = ?"
            params += it
        }
        query.after?.let { (at, id) ->
            conditions += "(e.occurred_at, e.id) < (?, ?)"
            params += at
            params += id
        }
        params += query.limit
        return connection(tx)
            .prepareStatement("$SELECT WHERE ${conditions.joinToString(" AND ")} ORDER BY e.occurred_at DESC, e.id DESC LIMIT ?")
            .use { it.bind(params).list(::entry) }
    }

    /** Every entry, in any chain, where [staffId] is the actor or the target, oldest first: a privacy export (§10). */
    fun about(
        tx: TxScope,
        staffId: StaffId,
    ): List<AuditEntry> =
        connection(tx)
            .prepareStatement("$SELECT WHERE e.actor_id = ? OR e.target_id = ? ORDER BY e.occurred_at, e.id")
            .use { it.bind(staffId.value, staffId.value).list(::entry) }

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)

    private companion object {
        // The display name only for staff and admin actors: a guest's actor ID is a device, not an account.
        const val SELECT =
            "SELECT e.id, e.occurred_at, e.action, e.actor_type, e.actor_id, a.display_name AS actor_name, e.target_type, " +
                "e.target_id, e.request_id, host(e.ip) AS ip, e.user_agent, e.details::text AS details FROM audit_events e " +
                "LEFT JOIN staff_accounts a ON a.id = e.actor_id AND e.actor_type IN ('staff', 'admin')"

        fun entry(rs: ResultSet) =
            AuditEntry(
                id = rs.uuid("id")!!,
                occurredAt = rs.instant("occurred_at")!!,
                action = rs.getString("action"),
                actorType = ActorType.of(rs.getString("actor_type")),
                actorId = rs.uuid("actor_id"),
                actorDisplayName = rs.getString("actor_name"),
                targetType = rs.getString("target_type"),
                targetId = rs.uuid("target_id"),
                requestId = rs.getString("request_id"),
                ip = rs.getString("ip"),
                userAgent = rs.getString("user_agent"),
                details = Json.parseToJsonElement(rs.getString("details")).jsonObject,
            )
    }
}
