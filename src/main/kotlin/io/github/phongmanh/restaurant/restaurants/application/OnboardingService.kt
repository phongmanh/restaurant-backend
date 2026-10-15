package io.github.phongmanh.restaurant.restaurants.application

import io.github.phongmanh.restaurant.core.application.Actor
import io.github.phongmanh.restaurant.core.application.AuditLog
import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.StaffRole
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.auth.application.IssuedLink
import io.github.phongmanh.restaurant.core.auth.application.StaffAccounts
import io.github.phongmanh.restaurant.core.auth.domain.AccountStatus
import io.github.phongmanh.restaurant.core.auth.domain.NewAccount
import io.github.phongmanh.restaurant.core.auth.domain.StaffMember
import io.github.phongmanh.restaurant.core.auth.persistence.LinkKind
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.IdGenerator
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.restaurants.domain.NewRestaurant
import io.github.phongmanh.restaurant.restaurants.domain.Restaurant
import io.github.phongmanh.restaurant.restaurants.domain.StaffRules
import io.github.phongmanh.restaurant.restaurants.persistence.RestaurantRepository
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock

/** The first manager of a new restaurant (restaurants §5). */
data class FirstManager(
    val email: String,
    val displayName: String,
    val phone: String?,
)

class Onboarded(
    val restaurant: Restaurant,
    val firstManager: StaffMember,
    val invitation: IssuedLink,
)

/**
 * Onboarding (FR-040, restaurants §5), in one platform transaction: the restaurant, its first
 * manager (invited) and their invitation link, audited in the **new restaurant's** chain so its
 * managers can see how it was set up. The route runs it under its `Idempotency-Key`.
 */
class OnboardingService(
    private val transactor: Transactor,
    private val restaurants: RestaurantRepository,
    private val accounts: StaffAccounts,
    private val audit: AuditLog,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun onboard(
        caller: Caller.Admin,
        restaurant: NewRestaurant,
        manager: FirstManager,
        request: RequestMeta,
    ): Onboarded =
        transactor.platformTransaction("restaurants: onboarding") {
            val now = clock.instant()
            val id = RestaurantId(ids.next())
            restaurants.insert(this, id, restaurant, now)
            // An email in use → 409, which rolls the restaurant back with everything else (§15 Q8).
            val member = accounts.create(this, NewAccount(manager.email, manager.displayName, manager.phone, id, StaffRole.MANAGER), now)
            val link = accounts.issueLink(this, member, LinkKind.INVITATION, caller.staffId, now)
            val actor = Actor.of(caller)
            audit.recordRestaurant(this, id, actor, "restaurant.onboarded", request)
            audit.recordAccount(this, member, actor, "staff.invited", request, mapOf("role" to JsonPrimitive("manager")))
            Onboarded(restaurants.find(this, id)!!, member, link)
        }

    /**
     * A replay of onboarding (core-and-auth §4): the stored answer holds no link, so the replay
     * issues a fresh one, which replaces the first. If the manager has accepted the invitation by
     * now, there's no invitation to give → `409 member_state_conflict`.
     */
    suspend fun reissueInvitation(
        caller: Caller.Admin,
        restaurantId: RestaurantId,
        managerId: StaffId,
        request: RequestMeta,
    ): IssuedLink =
        transactor.platformTransaction("restaurants: onboarding replay") {
            val member = accounts.find(this, restaurantId, managerId) ?: throw DomainError.NotFound()
            StaffRules.requireStatus(member, AccountStatus.INVITED)
            val link = accounts.issueLink(this, member, LinkKind.INVITATION, caller.staffId, clock.instant())
            audit.recordAccount(this, member, Actor.of(caller), "staff.invitation_reissued", request)
            link
        }
}
