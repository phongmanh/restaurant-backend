package io.github.phongmanh.restaurant.restaurants.application

import io.github.phongmanh.restaurant.core.application.AuditLog
import io.github.phongmanh.restaurant.core.application.RateLimits
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.StaffRole
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.auth.RateLimitSettings
import io.github.phongmanh.restaurant.core.auth.application.IssuedLink
import io.github.phongmanh.restaurant.core.auth.application.StaffAccounts
import io.github.phongmanh.restaurant.core.auth.domain.AccountStatus
import io.github.phongmanh.restaurant.core.auth.domain.MemberChanges
import io.github.phongmanh.restaurant.core.auth.domain.NewAccount
import io.github.phongmanh.restaurant.core.auth.domain.StaffMember
import io.github.phongmanh.restaurant.core.auth.persistence.LinkKind
import io.github.phongmanh.restaurant.core.auth.persistence.MemberFilter
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.restaurants.domain.StaffActor
import io.github.phongmanh.restaurant.restaurants.domain.StaffRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** A member to invite (restaurants §7), already checked against the field rules. */
data class MemberInvite(
    val email: String,
    val displayName: String,
    val phone: String?,
    val role: StaffRole,
)

/**
 * Staff management (restaurants §7) for a restaurant's managers and for platform admins. Every
 * operation runs in the restaurant's own transaction and finds the member by the scope's restaurant,
 * so another restaurant's staff ID is `404`. Changes take the restaurant's staff lock first, so the
 * last-manager check and the seat count can't race (two managers demoting each other leave one).
 */
class StaffService(
    private val transactor: Transactor,
    private val tenants: TenantStatus,
    private val accounts: StaffAccounts,
    private val audit: AuditLog,
    private val limits: RateLimits,
    private val limitSettings: RateLimitSettings,
    private val clock: Clock,
) {
    suspend fun list(
        scope: RestaurantScope,
        filter: MemberFilter,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<StaffMember> =
        transactor.inTransaction(scope.restaurantId) {
            requireRestaurant(scope)
            accounts.list(this, scope.restaurantId, filter, after, limit)
        }

    suspend fun get(
        scope: RestaurantScope,
        id: StaffId,
    ): StaffMember =
        transactor.inTransaction(scope.restaurantId) {
            requireRestaurant(scope)
            member(scope, id)
        }

    /** A new invited member and their invitation link (72 h). An email in use anywhere → `409 email_in_use`. */
    suspend fun invite(
        scope: RestaurantScope,
        invite: MemberInvite,
        request: RequestMeta,
    ): Pair<StaffMember, IssuedLink> {
        checkLinkLimit(scope)
        return change(scope) { now ->
            if (accounts.counts(this, scope.restaurantId).seats >= StaffRules.MAX_SEATS) throw DomainError.MemberLimitReached()
            val account = NewAccount(invite.email, invite.displayName, invite.phone, scope.restaurantId, invite.role)
            val member = accounts.create(this, account, now)
            val link = accounts.issueLink(this, member, LinkKind.INVITATION, scope.staffId, now)
            record(scope, "staff.invited", member, request, mapOf("role" to role(invite.role)))
            member to link
        }
    }

    /** Name, phone or role (§7); a role change ends the member's sessions. A manager is held to the last-manager rule. */
    suspend fun update(
        scope: RestaurantScope,
        id: StaffId,
        version: Int,
        changes: MemberChanges,
        request: RequestMeta,
    ): StaffMember =
        change(scope) { now ->
            val member = member(scope, id)
            val demoted = member.role == StaffRole.MANAGER && changes.role != null && changes.role != StaffRole.MANAGER
            val managers = if (demoted) accounts.counts(this, scope.restaurantId).activeManagers else null
            if (managers != null) StaffRules.checkLastManager(scope.actor, member, managers)
            val updated = accounts.update(this, member, version, changes, now)
            val details = mutableMapOf<String, JsonElement>("fields" to JsonArray(changed(changes).map(::JsonPrimitive)))
            if (changes.role != null && changes.role != member.role) {
                details["role"] = JsonObject(mapOf("from" to role(member.role!!), "to" to role(changes.role)))
            }
            record(scope, "staff.updated", member, request, details + lastManagerRemoved(member, managers))
            updated
        }

    /** Deactivates the member and ends their sessions; one already deactivated is returned as it is. */
    suspend fun deactivate(
        scope: RestaurantScope,
        id: StaffId,
        request: RequestMeta,
    ): StaffMember =
        change(scope) { now ->
            val member = member(scope, id)
            if (member.status == AccountStatus.DEACTIVATED) return@change member
            val managers = if (member.isActiveManager) accounts.counts(this, scope.restaurantId).activeManagers else null
            if (managers != null) StaffRules.checkLastManager(scope.actor, member, managers)
            val deactivated = accounts.deactivate(this, member, now)
            record(scope, "staff.deactivated", member, request, lastManagerRemoved(member, managers))
            deactivated
        }

    /** Restores a deactivated member who isn't anonymised yet; anyone else is returned as they are. */
    suspend fun reactivate(
        scope: RestaurantScope,
        id: StaffId,
        request: RequestMeta,
    ): StaffMember =
        change(scope) { now ->
            val member = member(scope, id)
            if (member.status != AccountStatus.DEACTIVATED) return@change member
            if (accounts.counts(this, scope.restaurantId).seats >= StaffRules.MAX_SEATS) throw DomainError.MemberLimitReached()
            val reactivated = accounts.reactivate(this, member, now)
            record(scope, "staff.reactivated", member, request)
            reactivated
        }

    /** A fresh invitation link for an invited member; the old one stops working. */
    suspend fun reissueInvitation(
        scope: RestaurantScope,
        id: StaffId,
        request: RequestMeta,
    ): IssuedLink {
        checkLinkLimit(scope)
        return change(scope) { now ->
            val member = member(scope, id)
            StaffRules.requireStatus(member, AccountStatus.INVITED)
            accounts.issueLink(this, member, LinkKind.INVITATION, scope.staffId, now).also {
                record(scope, "staff.invitation_reissued", member, request)
            }
        }
    }

    /** A password-reset link (24 h) for an active member. A manager may reset waiters and kitchen only. */
    suspend fun issuePasswordReset(
        scope: RestaurantScope,
        id: StaffId,
        request: RequestMeta,
    ): IssuedLink {
        checkLinkLimit(scope)
        return change(scope) { now ->
            val member = member(scope, id)
            StaffRules.checkMayReset(scope.actor, member)
            StaffRules.requireStatus(member, AccountStatus.ACTIVE)
            accounts.issueLink(this, member, LinkKind.PASSWORD_RESET, scope.staffId, now).also {
                record(scope, "staff.password_reset_issued", member, request)
            }
        }
    }

    /** The restaurant's transaction with its staff lock held, for a change made at one instant. */
    private suspend fun <T> change(
        scope: RestaurantScope,
        block: suspend TxScope.(Instant) -> T,
    ): T =
        transactor.inTransaction(scope.restaurantId) {
            requireRestaurant(scope)
            accounts.lockStaffOf(this, scope.restaurantId)
            block(clock.instant())
        }

    /** An admin's path may name a restaurant that doesn't exist → `404` (a manager's always does). */
    private fun TxScope.requireRestaurant(scope: RestaurantScope) {
        if (tenants.of(this, scope.restaurantId) == null) throw DomainError.NotFound()
    }

    private fun TxScope.member(
        scope: RestaurantScope,
        id: StaffId,
    ): StaffMember = accounts.find(this, scope.restaurantId, id) ?: throw DomainError.NotFound()

    /** At most 30 links per restaurant an hour, counted before the work (§7, §15 Q6). */
    private suspend fun checkLinkLimit(scope: RestaurantScope) =
        limits.check(limitSettings.policy(StaffLimits.LINK), scope.restaurantId.toString())

    private fun TxScope.record(
        scope: RestaurantScope,
        action: String,
        member: StaffMember,
        request: RequestMeta,
        details: Map<String, JsonElement> = emptyMap(),
    ) = audit.recordAccount(this, member, scope.auditActor, action, request, details)

    /**
     * Set on an admin's change that left the restaurant without an active manager, which the
     * last-manager rule lets admins do (§7); [managers] is the count before the change.
     */
    private fun lastManagerRemoved(
        member: StaffMember,
        managers: Int?,
    ): Map<String, JsonElement> =
        if (member.isActiveManager && managers == 1) mapOf("last_manager_removed" to JsonPrimitive(true)) else emptyMap()

    private fun changed(changes: MemberChanges): List<String> =
        listOfNotNull(changes.displayName?.let { "display_name" }, changes.phone?.let { "phone" }, changes.role?.let { "role" })

    private fun role(role: StaffRole) = JsonPrimitive(role.name.lowercase(Locale.ROOT))
}
