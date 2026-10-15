package io.github.phongmanh.restaurant.restaurants.application

import io.github.phongmanh.restaurant.core.application.Actor
import io.github.phongmanh.restaurant.core.application.ActorType
import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.StaffRole
import io.github.phongmanh.restaurant.core.application.requireRole
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.restaurants.domain.StaffActor

/**
 * Who manages a restaurant, and which one (restaurants §3): one of its managers, for their own
 * restaurant (taken from the caller, never the path), or a platform admin, for the restaurant in
 * the path.
 *
 * The only way to get one is the role check, so a service that takes a scope can't run without it,
 * and a route makes it before it reads the request: the check order is 401 → 403 → 400 → 404
 * (core-and-auth §3).
 */
class RestaurantScope private constructor(
    val restaurantId: RestaurantId,
    val actor: StaffActor,
    val staffId: StaffId,
) {
    /** Who the audit log names. */
    val auditActor: Actor
        get() = Actor(if (actor == StaffActor.MANAGER) ActorType.STAFF else ActorType.ADMIN, staffId.value)

    companion object {
        /** A manager of their own restaurant; any other role → `403 forbidden`. */
        fun manager(caller: Caller.Staff): RestaurantScope {
            caller.requireRole(StaffRole.MANAGER)
            return RestaurantScope(caller.restaurantId, StaffActor.MANAGER, caller.staffId)
        }

        /** A platform admin acting on [restaurantId]; the admin route group already checked who they are. */
        fun admin(
            caller: Caller.Admin,
            restaurantId: RestaurantId,
        ): RestaurantScope = RestaurantScope(restaurantId, StaffActor.PLATFORM_ADMIN, caller.staffId)
    }
}

/** The rate-limit policies of this feature (restaurants §7, §15 Q6). */
object StaffLimits {
    /** Invitation and reset links per restaurant: 30 an hour. */
    const val LINK = "staff.link"
}
