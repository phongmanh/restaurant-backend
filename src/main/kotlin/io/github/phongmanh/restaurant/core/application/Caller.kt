package io.github.phongmanh.restaurant.core.application

import io.github.phongmanh.restaurant.core.domain.DeviceId
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.domain.StaffSessionId
import io.github.phongmanh.restaurant.core.domain.TableId
import io.github.phongmanh.restaurant.core.domain.TableSessionId

enum class StaffRole { MANAGER, WAITER, KITCHEN }

/**
 * Who is calling (core-and-auth §6). Every service method receives one. It's built from the token
 * and the database by the route group's authentication, never from the request body.
 */
sealed interface Caller {
    /**
     * The scope of this caller's `Idempotency-Key`s (§4), so two devices or two staff members can't
     * collide or replay each other's answers.
     */
    val idempotencyScope: String

    data class Guest(
        val deviceId: DeviceId,
        val sessionId: TableSessionId,
        val tableId: TableId,
        val restaurantId: RestaurantId,
    ) : Caller {
        override val idempotencyScope: String get() = "guest:$deviceId"
    }

    data class Staff(
        val staffId: StaffId,
        val restaurantId: RestaurantId,
        val role: StaffRole,
        val sessionId: StaffSessionId,
    ) : Caller {
        override val idempotencyScope: String get() = "staff:$staffId"
    }

    data class Admin(
        val staffId: StaffId,
        val sessionId: StaffSessionId,
    ) : Caller {
        override val idempotencyScope: String get() = "admin:$staffId"
    }
}

/**
 * The role check, in the service so a second route to the same operation can't skip it (§6). It
 * doesn't look at the object, so a 403 can't reveal that another restaurant's object exists (§3).
 */
fun Caller.Staff.requireRole(vararg allowed: StaffRole) = requireRole(allowed.toSet())

/** [requireRole] for a role set a feature keeps, such as the roles of an order step (orders §2). */
fun Caller.Staff.requireRole(allowed: Set<StaffRole>) {
    if (role !in allowed) throw DomainError.Forbidden()
}
