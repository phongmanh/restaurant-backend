package io.github.phongmanh.restaurant.tables.application

import io.github.phongmanh.restaurant.core.application.Actor
import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.StaffRole
import io.github.phongmanh.restaurant.core.application.requireRole
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId

/**
 * A manager working on their own restaurant's tables and QR codes (tables §6). The only way to get
 * one is the role check, so the route makes it before it reads the request: 401 → 403 → 400 → 404
 * (core-and-auth §3).
 */
class TableManager private constructor(
    val restaurantId: RestaurantId,
    val actor: Actor,
) {
    companion object {
        /** A manager; any other role → `403 forbidden`. */
        fun of(caller: Caller.Staff): TableManager {
            caller.requireRole(StaffRole.MANAGER)
            return TableManager(caller.restaurantId, Actor.of(caller))
        }
    }
}

/**
 * A waiter or manager on the floor (tables §3.4, §4, §6): the floor view, opening and moving
 * sessions, answering waiter calls. Kitchen staff → `403`.
 */
class FloorStaff private constructor(
    val restaurantId: RestaurantId,
    val staffId: StaffId,
    val actor: Actor,
) {
    companion object {
        fun of(caller: Caller.Staff): FloorStaff {
            caller.requireRole(StaffRole.WAITER, StaffRole.MANAGER)
            return FloorStaff(caller.restaurantId, caller.staffId, Actor.of(caller))
        }
    }
}

/** The rate-limit policies of this feature (tables §9, §13 Q4). */
object TableLimits {
    /** QR scans per device, per table and per IP (a restaurant's guests may share Wi-Fi). */
    const val QR_DEVICE = "qr.present_device"
    const val QR_TABLE = "qr.present_table"
    const val QR_IP = "qr.present_ip"

    /** New waiter calls per session; merged taps don't count. */
    const val WAITER_CALL = "waiter_call"
}

/** The events this feature writes (realtime §5) and the aggregates they're about. */
object TableEvents {
    const val SESSION_OPENED = "TableSessionOpened"
    const val SESSION_MOVED = "TableSessionMoved"
    const val SESSION_CLOSED = "TableSessionClosed"
    const val WAITER_CALLED = "WaiterCalled"
    const val WAITER_CALL_ACKNOWLEDGED = "WaiterCallAcknowledged"

    const val SESSION_AGGREGATE = "table_session"
    const val CALL_AGGREGATE = "waiter_call"
}

/** Audit target types (tables §6 "Audit"). */
object TableTargets {
    const val TABLE = "table"
    const val SESSION = "table_session"
    const val WAITER_CALL = "waiter_call"
}
