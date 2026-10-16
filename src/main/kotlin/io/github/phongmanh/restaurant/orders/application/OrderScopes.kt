package io.github.phongmanh.restaurant.orders.application

import io.github.phongmanh.restaurant.core.application.Actor
import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.RateLimits
import io.github.phongmanh.restaurant.core.application.StaffRole
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.application.requireRole
import io.github.phongmanh.restaurant.core.auth.RateLimitSettings
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.orders.domain.OrderStatus
import io.github.phongmanh.restaurant.orders.domain.Transitions
import io.github.phongmanh.restaurant.restaurants.application.RestaurantDirectory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * The role scopes of this feature (orders §2, §12). Each is made only by its role check, so a route
 * makes it before it reads the body, `If-Match` or the query: 401 → 403 → 400 → 404 → 409/412
 * (core-and-auth §3). The check never looks at the order, so a 403 can't reveal that one exists.
 */

/** A waiter or manager: placing orders for a table (FR-014), closing a session, the day's bills. Kitchen → `403`. */
class BillStaff private constructor(
    val restaurantId: RestaurantId,
    val staffId: StaffId,
    val actor: Actor,
    val idempotencyScope: String,
) {
    companion object {
        fun of(caller: Caller.Staff): BillStaff {
            caller.requireRole(StaffRole.WAITER, StaffRole.MANAGER)
            return BillStaff(caller.restaurantId, caller.staffId, Actor.of(caller), caller.idempotencyScope)
        }
    }
}

/** A manager correcting a closed bill (orders §7.2). Any other role → `403`. */
class BillManager private constructor(
    val restaurantId: RestaurantId,
    val staffId: StaffId,
    val actor: Actor,
) {
    companion object {
        fun of(caller: Caller.Staff): BillManager {
            caller.requireRole(StaffRole.MANAGER)
            return BillManager(caller.restaurantId, caller.staffId, Actor.of(caller))
        }
    }
}

/** A staff member allowed to move an order to [to] (orders §2); the role is checked against the target only. */
class OrderStep private constructor(
    val restaurantId: RestaurantId,
    val staffId: StaffId,
    val actor: Actor,
    val to: OrderStatus,
) {
    companion object {
        fun of(
            caller: Caller.Staff,
            to: OrderStatus,
        ): OrderStep {
            caller.requireRole(Transitions.rolesFor(to))
            return OrderStep(caller.restaurantId, caller.staffId, Actor.of(caller), to)
        }
    }
}

/** Kitchen or manager rejecting one line (orders §5.3, FR-015). Waiter → `403`. */
class LineRejecter private constructor(
    val restaurantId: RestaurantId,
    val staffId: StaffId,
    val actor: Actor,
) {
    companion object {
        fun of(caller: Caller.Staff): LineRejecter {
            caller.requireRole(Transitions.lineRejecters)
            return LineRejecter(caller.restaurantId, caller.staffId, Actor.of(caller))
        }
    }
}

/** Who places an order: a guest of the session, or a staff member for it (FR-014). */
class Placer private constructor(
    val restaurantId: RestaurantId,
    val sessionId: TableSessionId,
    /** `null` for a guest. */
    val staffId: StaffId?,
    val actor: Actor,
) {
    companion object {
        fun guest(guest: Caller.Guest) = Placer(guest.restaurantId, guest.sessionId, null, Actor.of(guest))

        fun staff(
            staff: BillStaff,
            sessionId: TableSessionId,
        ) = Placer(staff.restaurantId, sessionId, staff.staffId, staff.actor)
    }
}

/** The rate-limit policies of this feature (orders §10, §15 Q6). */
object OrderLimitPolicies {
    /** New orders per session (ASSUMPTION-4) and per restaurant (only stops a flood). */
    const val PLACE_SESSION = "order.place_session"
    const val PLACE_RESTAURANT = "order.place_restaurant"

    /** Orders a staff member places for tables. */
    const val PLACE_STAFF = "order.place_staff"

    /** Guest cancels per session. */
    const val CANCEL = "order.cancel"

    /** Staff status changes and line rejections per staff member. */
    const val STAFF_CHANGE = "order.staff_change"
}

/** Applies [OrderLimitPolicies] before the transaction (orders §3.1 step 1). Over a limit → `429` with `Retry-After`. */
class OrderRateLimits(
    private val limits: RateLimits,
    private val settings: RateLimitSettings,
) {
    suspend fun guestOrder(
        restaurantId: RestaurantId,
        sessionId: TableSessionId,
    ) {
        limits.check(settings.policy(OrderLimitPolicies.PLACE_SESSION), sessionId.toString())
        limits.check(settings.policy(OrderLimitPolicies.PLACE_RESTAURANT), restaurantId.toString())
    }

    suspend fun staffOrder(
        restaurantId: RestaurantId,
        staffId: StaffId,
    ) {
        limits.check(settings.policy(OrderLimitPolicies.PLACE_STAFF), staffId.toString())
        limits.check(settings.policy(OrderLimitPolicies.PLACE_RESTAURANT), restaurantId.toString())
    }

    suspend fun guestCancel(sessionId: TableSessionId) = limits.check(settings.policy(OrderLimitPolicies.CANCEL), sessionId.toString())

    suspend fun staffChange(staffId: StaffId) = limits.check(settings.policy(OrderLimitPolicies.STAFF_CHANGE), staffId.toString())
}

/** The events this feature writes (orders §9, realtime §5) and the aggregates they're about. */
object OrderEvents {
    const val ORDER_PLACED = "OrderPlaced"
    const val ORDER_STATUS_CHANGED = "OrderStatusChanged"
    const val ORDER_LINE_REJECTED = "OrderLineRejected"
    const val BILL_CLOSED = "BillClosed"
    const val BILL_ADJUSTED = "BillAdjusted"

    const val ORDER_AGGREGATE = "order"

    /** A bill's ID is its session's. */
    const val BILL_AGGREGATE = "bill"
}

/** Audit target types (orders §8). */
object OrderTargets {
    const val ORDER = "order"
    const val BILL = "bill"
}

/**
 * The restaurant's local day for a moment (ASSUMPTION-11): a bill closed at 00:30 local counts on
 * the new day (IT-ORD-16). The time zone comes from `RestaurantDirectory` (restaurants §6).
 */
class BusinessDay(
    private val directory: RestaurantDirectory,
) {
    fun of(
        tx: TxScope,
        restaurantId: RestaurantId,
        at: Instant,
    ): LocalDate {
        val zone = directory.profile(tx, restaurantId)?.timeZone ?: error("no restaurant $restaurantId")
        return LocalDate.ofInstant(at, ZoneId.of(zone))
    }
}
