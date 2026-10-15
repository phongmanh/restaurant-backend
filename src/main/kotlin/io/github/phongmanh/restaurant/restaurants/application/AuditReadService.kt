package io.github.phongmanh.restaurant.restaurants.application

import io.github.phongmanh.restaurant.core.application.Actor
import io.github.phongmanh.restaurant.core.application.AuditEvent
import io.github.phongmanh.restaurant.core.application.AuditLog
import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.restaurants.persistence.AuditEntry
import io.github.phongmanh.restaurant.restaurants.persistence.AuditQuery
import io.github.phongmanh.restaurant.restaurants.persistence.AuditQueryRepository
import io.github.phongmanh.restaurant.restaurants.persistence.RestaurantRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Which chain an admin reads: one restaurant's, or the platform's (restaurants §9). */
sealed interface AuditChain {
    data class Of(
        val restaurantId: RestaurantId,
    ) : AuditChain

    data object Platform : AuditChain
}

/**
 * Reading the audit log (restaurants §9). A manager reads their restaurant's chain without IP and
 * user agent (§15 Q7), and that read isn't audited. An admin reads any chain with everything, and
 * the read is itself audited (`admin.audit_read`), because admins read across tenants.
 */
class AuditReadService(
    private val transactor: Transactor,
    private val entries: AuditQueryRepository,
    private val restaurants: RestaurantRepository,
    private val audit: AuditLog,
) {
    /** The manager's view: IP and user agent are left out here, not only in the response. */
    suspend fun own(
        scope: RestaurantScope,
        query: AuditQuery,
    ): List<AuditEntry> =
        transactor
            .inTransaction(scope.restaurantId) { entries.page(this, scope.restaurantId, query) }
            .map { it.copy(ip = null, userAgent = null) }

    suspend fun admin(
        caller: Caller.Admin,
        chain: AuditChain,
        query: AuditQuery,
        request: RequestMeta,
    ): List<AuditEntry> =
        transactor.platformTransaction("audit: admin read") {
            val restaurantId = (chain as? AuditChain.Of)?.restaurantId
            if (restaurantId != null && restaurants.status(this, restaurantId) == null) throw DomainError.NotFound()
            val page = entries.page(this, restaurantId, query)
            val read = JsonPrimitive(restaurantId?.toString() ?: "platform")
            audit.record(
                this,
                AuditEvent(null, Actor.of(caller), "admin.audit_read", details = JsonObject(mapOf("chain" to read)), request = request),
            )
            page
        }
}
