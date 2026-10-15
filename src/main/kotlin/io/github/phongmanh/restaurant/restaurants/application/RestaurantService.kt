package io.github.phongmanh.restaurant.restaurants.application

import io.github.phongmanh.restaurant.core.application.Actor
import io.github.phongmanh.restaurant.core.application.AuditEvent
import io.github.phongmanh.restaurant.core.application.AuditLog
import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.application.requireVersionedUpdate
import io.github.phongmanh.restaurant.core.auth.application.AuthAudit
import io.github.phongmanh.restaurant.core.auth.domain.StaffMember
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.restaurants.domain.ProfileChanges
import io.github.phongmanh.restaurant.restaurants.domain.Restaurant
import io.github.phongmanh.restaurant.restaurants.domain.RestaurantStatus
import io.github.phongmanh.restaurant.restaurants.domain.StaffActor
import io.github.phongmanh.restaurant.restaurants.persistence.RestaurantRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.util.UUID

/** The restaurant profile (restaurants §4) and the admins' restaurant list. */
class RestaurantService(
    private val transactor: Transactor,
    private val restaurants: RestaurantRepository,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /** The caller's own restaurant, for every role (§4.1). */
    suspend fun own(caller: Caller.Staff): Restaurant = get(caller.restaurantId)

    /** One restaurant, in its own transaction, so even an admin's read sees only its rows. */
    suspend fun get(id: RestaurantId): Restaurant =
        transactor.inTransaction(id) { restaurants.find(this, id) } ?: throw DomainError.NotFound()

    /** A page of restaurants for platform admins, by name (§11: `?status=`, `?q=` name prefix). */
    suspend fun list(
        status: RestaurantStatus?,
        namePrefix: String?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Restaurant> =
        transactor.platformTransaction("restaurants: admin list") { restaurants.list(this, status, namePrefix, after, limit) }

    /**
     * A profile edit (§4.2) if [version] is still current: `404` if the restaurant isn't there, `412`
     * if someone saved in between. Only an admin may change the owner contact (§3).
     */
    suspend fun update(
        scope: RestaurantScope,
        version: Int,
        changes: ProfileChanges,
        request: RequestMeta,
    ): Restaurant {
        require(changes.ownerContact == null || scope.actor == StaffActor.PLATFORM_ADMIN) { "only admins change the owner contact" }
        val id = scope.restaurantId
        return transactor.inTransaction(id) {
            requireVersionedUpdate(restaurants.update(this, id, version, changes, clock.instant())) { restaurants.find(this, id) != null }
            val fields = mapOf("fields" to JsonArray(changes.fieldNames.map(::JsonPrimitive)))
            audit.recordRestaurant(this, id, scope.auditActor, "restaurant.profile_updated", request, fields)
            restaurants.find(this, id)!!
        }
    }
}

/** Suspension and reactivation (restaurants §6): both naturally idempotent, so no key and no `If-Match`. */
class SuspensionService(
    private val transactor: Transactor,
    private val restaurants: RestaurantRepository,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /**
     * Suspends the restaurant; one already suspended is returned as it is. The audit entry leaves
     * the reason out: it's free text, which the audit log doesn't hold, and the restaurant's
     * managers read this chain while the reason is for admins only (§2, §9).
     */
    suspend fun suspend(
        caller: Caller.Admin,
        id: RestaurantId,
        reason: String,
        request: RequestMeta,
    ): Restaurant =
        transactor.inTransaction(id) {
            restaurants.find(this, id) ?: throw DomainError.NotFound()
            if (restaurants.suspend(this, id, reason, clock.instant())) {
                audit.recordRestaurant(this, id, Actor.of(caller), "restaurant.suspended", request)
            }
            restaurants.find(this, id)!!
        }

    /** Reactivates the restaurant; nothing was deleted or revoked, so everything works again (§6). */
    suspend fun reactivate(
        caller: Caller.Admin,
        id: RestaurantId,
        request: RequestMeta,
    ): Restaurant =
        transactor.inTransaction(id) {
            restaurants.find(this, id) ?: throw DomainError.NotFound()
            if (restaurants.reactivate(this, id, clock.instant())) {
                audit.recordRestaurant(this, id, Actor.of(caller), "restaurant.reactivated", request)
            }
            restaurants.find(this, id)!!
        }
}

/** What other features read of a restaurant (restaurants §6): the `TenantStatus` port. */
fun interface TenantStatus {
    /** `null` if there's no such restaurant. */
    fun of(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): RestaurantStatus?
}

/** The name, time zone and languages, for menus, guest views and reports. */
data class RestaurantProfile(
    val name: String,
    val timeZone: String,
    val languages: List<String>,
)

/** The `RestaurantDirectory` port (restaurants §6). */
fun interface RestaurantDirectory {
    fun profile(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): RestaurantProfile?
}

/** Both ports, in the caller's transaction. */
class RestaurantPorts(
    private val restaurants: RestaurantRepository,
) : TenantStatus,
    RestaurantDirectory {
    override fun of(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): RestaurantStatus? = restaurants.status(tx, restaurantId)

    override fun profile(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): RestaurantProfile? = restaurants.find(tx, restaurantId)?.let { RestaurantProfile(it.name, it.timeZone, it.languages) }
}

const val RESTAURANT_TARGET = "restaurant"

/** An entry about the restaurant itself, in its own chain (restaurants §4–6). */
fun AuditLog.recordRestaurant(
    tx: TxScope,
    id: RestaurantId,
    actor: Actor,
    action: String,
    request: RequestMeta,
    details: Map<String, JsonElement> = emptyMap(),
) = record(tx, AuditEvent(id, actor, action, RESTAURANT_TARGET, id.value, JsonObject(details), request))

/** An entry about a staff account: in its restaurant's chain, or the platform's for an admin (restaurants §7–10). */
fun AuditLog.recordAccount(
    tx: TxScope,
    account: StaffMember,
    actor: Actor,
    action: String,
    request: RequestMeta?,
    details: Map<String, JsonElement> = emptyMap(),
) = record(tx, AuditEvent(account.restaurantId, actor, action, AuthAudit.TARGET_TYPE, account.id.value, JsonObject(details), request))
