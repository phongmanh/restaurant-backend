package io.github.phongmanh.restaurant.core.http

import io.ktor.http.HttpMethod

/**
 * The route groups. The path prefix decides the authentication (core-and-auth §2), so an endpoint
 * belongs to the group its path starts with.
 */
enum class RouteGroup(
    val prefix: String,
) {
    AUTH("/v1/auth"),
    GUEST("/v1/guest"),
    STAFF("/v1/staff"),
    ADMIN("/v1/admin"),
    ;

    companion object {
        fun of(path: String): RouteGroup =
            entries.firstOrNull { path == it.prefix || path.startsWith(it.prefix + "/") }
                ?: error("$path is in no route group")
    }
}

/**
 * One operation of the API contract. [path] uses the contract's `{name}` placeholders, which Ktor
 * reads the same way.
 *
 * - [idempotencyKey]: the operation requires `Idempotency-Key` (core-and-auth §4).
 * - [ifMatch]: the resource is versioned and the operation requires `If-Match` (core-and-auth §5).
 * - [allowedWhenSuspended]: callers of a suspended restaurant may still use it (restaurants §6). It
 *   has no default, so every entry decides it.
 */
data class Endpoint(
    val method: HttpMethod,
    val path: String,
    val idempotencyKey: Boolean,
    val ifMatch: Boolean,
    val allowedWhenSuspended: Boolean,
) {
    val group: RouteGroup = RouteGroup.of(path)

    override fun toString(): String = "${method.value} $path"
}

/** The entries of one contract tag. Each object keeps its own list, filled as it initialises. */
abstract class EndpointGroup {
    private val declared = mutableListOf<Endpoint>()

    val entries: List<Endpoint> get() = declared

    protected fun endpoint(
        method: HttpMethod,
        path: String,
        idempotencyKey: Boolean = false,
        ifMatch: Boolean = false,
        allowedWhenSuspended: Boolean,
    ): Endpoint = Endpoint(method, path, idempotencyKey, ifMatch, allowedWhenSuspended).also { declared += it }
}

/**
 * Every endpoint of `docs/api/openapi.yaml`, declared once (owner, 2026-10-15; core-and-auth §16).
 * Route files use these entries, never path literals. Objects follow the contract's tags and names
 * follow its `operationId`s. IT-CORE-10 fails if this list, the contract and Ktor's routes differ.
 *
 * [HEALTH] and the `/internal/jobs/` paths are declared here too, but they aren't in the contract
 * or in [all] (core-and-auth §2).
 */
@Suppress("ktlint:standard:property-naming")
object Endpoints {
    /** Platform health check: 200 without touching the database. */
    const val HEALTH = "/health"

    /** All entries, in declaration order. A group missing here is caught by IT-CORE-10. */
    val all: List<Endpoint> by lazy {
        listOf(Auth, Restaurants, Staff, Audit, Admin, Privacy, Menu, Tables, Orders, Realtime, Reports)
            .flatMap { it.entries }
    }

    object Auth : EndpointGroup() {
        val SIGN_IN = endpoint(HttpMethod.Post, "/v1/auth/sign-in", allowedWhenSuspended = true)
        val COMPLETE_MFA = endpoint(HttpMethod.Post, "/v1/auth/mfa", allowedWhenSuspended = true)
        val REFRESH_TOKENS = endpoint(HttpMethod.Post, "/v1/auth/refresh", allowedWhenSuspended = true)
        val SIGN_OUT = endpoint(HttpMethod.Post, "/v1/auth/sign-out", allowedWhenSuspended = true)
        val INSPECT_LINK = endpoint(HttpMethod.Post, "/v1/auth/links/inspect", allowedWhenSuspended = true)
        val ACCEPT_INVITATION = endpoint(HttpMethod.Post, "/v1/auth/invitations/accept", allowedWhenSuspended = true)
        val COMPLETE_PASSWORD_RESET = endpoint(HttpMethod.Post, "/v1/auth/password-resets/complete", allowedWhenSuspended = true)
        val CHANGE_PASSWORD = endpoint(HttpMethod.Post, "/v1/auth/password", allowedWhenSuspended = true)
        val GET_ME = endpoint(HttpMethod.Get, "/v1/auth/me", allowedWhenSuspended = true)
    }

    object Restaurants : EndpointGroup() {
        val GET_OWN_RESTAURANT = endpoint(HttpMethod.Get, "/v1/staff/restaurant", allowedWhenSuspended = true)
        val UPDATE_OWN_RESTAURANT = endpoint(HttpMethod.Patch, "/v1/staff/restaurant", ifMatch = true, allowedWhenSuspended = false)
    }

    object Staff : EndpointGroup() {
        val LIST_MEMBERS = endpoint(HttpMethod.Get, "/v1/staff/members", allowedWhenSuspended = false)
        val INVITE_MEMBER = endpoint(HttpMethod.Post, "/v1/staff/members", allowedWhenSuspended = false)
        val GET_MEMBER = endpoint(HttpMethod.Get, "/v1/staff/members/{staffId}", allowedWhenSuspended = false)
        val UPDATE_MEMBER = endpoint(HttpMethod.Patch, "/v1/staff/members/{staffId}", ifMatch = true, allowedWhenSuspended = false)
        val DEACTIVATE_MEMBER = endpoint(HttpMethod.Post, "/v1/staff/members/{staffId}/deactivate", allowedWhenSuspended = false)
        val REACTIVATE_MEMBER = endpoint(HttpMethod.Post, "/v1/staff/members/{staffId}/reactivate", allowedWhenSuspended = false)
        val INVITATION_MEMBER = endpoint(HttpMethod.Post, "/v1/staff/members/{staffId}/invitation", allowedWhenSuspended = false)
        val PASSWORD_RESET_MEMBER = endpoint(HttpMethod.Post, "/v1/staff/members/{staffId}/password-reset", allowedWhenSuspended = false)
        val LIST_RESTAURANT_MEMBERS = endpoint(HttpMethod.Get, "/v1/admin/restaurants/{restaurantId}/members", allowedWhenSuspended = true)
        val INVITE_RESTAURANT_MEMBER =
            endpoint(HttpMethod.Post, "/v1/admin/restaurants/{restaurantId}/members", allowedWhenSuspended = true)
        val GET_RESTAURANT_MEMBER =
            endpoint(HttpMethod.Get, "/v1/admin/restaurants/{restaurantId}/members/{staffId}", allowedWhenSuspended = true)
        val UPDATE_RESTAURANT_MEMBER =
            endpoint(
                HttpMethod.Patch,
                "/v1/admin/restaurants/{restaurantId}/members/{staffId}",
                ifMatch = true,
                allowedWhenSuspended = true,
            )
        val DEACTIVATE_RESTAURANT_MEMBER =
            endpoint(HttpMethod.Post, "/v1/admin/restaurants/{restaurantId}/members/{staffId}/deactivate", allowedWhenSuspended = true)
        val REACTIVATE_RESTAURANT_MEMBER =
            endpoint(HttpMethod.Post, "/v1/admin/restaurants/{restaurantId}/members/{staffId}/reactivate", allowedWhenSuspended = true)
        val INVITATION_RESTAURANT_MEMBER =
            endpoint(HttpMethod.Post, "/v1/admin/restaurants/{restaurantId}/members/{staffId}/invitation", allowedWhenSuspended = true)
        val PASSWORD_RESET_RESTAURANT_MEMBER =
            endpoint(HttpMethod.Post, "/v1/admin/restaurants/{restaurantId}/members/{staffId}/password-reset", allowedWhenSuspended = true)
    }

    object Audit : EndpointGroup() {
        val LIST_OWN_AUDIT_EVENTS = endpoint(HttpMethod.Get, "/v1/staff/audit-events", allowedWhenSuspended = true)
        val LIST_AUDIT_EVENTS = endpoint(HttpMethod.Get, "/v1/admin/audit-events", allowedWhenSuspended = true)
    }

    object Admin : EndpointGroup() {
        val LIST_RESTAURANTS = endpoint(HttpMethod.Get, "/v1/admin/restaurants", allowedWhenSuspended = true)
        val ONBOARD_RESTAURANT = endpoint(HttpMethod.Post, "/v1/admin/restaurants", idempotencyKey = true, allowedWhenSuspended = true)
        val GET_RESTAURANT = endpoint(HttpMethod.Get, "/v1/admin/restaurants/{restaurantId}", allowedWhenSuspended = true)
        val UPDATE_RESTAURANT =
            endpoint(HttpMethod.Patch, "/v1/admin/restaurants/{restaurantId}", ifMatch = true, allowedWhenSuspended = true)
        val SUSPEND_RESTAURANT = endpoint(HttpMethod.Post, "/v1/admin/restaurants/{restaurantId}/suspend", allowedWhenSuspended = true)
        val REACTIVATE_RESTAURANT =
            endpoint(HttpMethod.Post, "/v1/admin/restaurants/{restaurantId}/reactivate", allowedWhenSuspended = true)
        val LIST_ADMINS = endpoint(HttpMethod.Get, "/v1/admin/admins", allowedWhenSuspended = true)
        val INVITE_ADMIN = endpoint(HttpMethod.Post, "/v1/admin/admins", allowedWhenSuspended = true)
        val DEACTIVATE_ADMIN = endpoint(HttpMethod.Post, "/v1/admin/admins/{staffId}/deactivate", allowedWhenSuspended = true)
        val PASSWORD_RESET_ADMIN = endpoint(HttpMethod.Post, "/v1/admin/admins/{staffId}/password-reset", allowedWhenSuspended = true)
        val TOTP_RESET_ADMIN = endpoint(HttpMethod.Post, "/v1/admin/admins/{staffId}/totp-reset", allowedWhenSuspended = true)
    }

    object Privacy : EndpointGroup() {
        val EXPORT_STAFF_DATA = endpoint(HttpMethod.Get, "/v1/admin/staff-accounts/{staffId}/export", allowedWhenSuspended = true)
        val ERASE_STAFF_DATA = endpoint(HttpMethod.Post, "/v1/admin/staff-accounts/{staffId}/erase", allowedWhenSuspended = true)
    }

    object Menu : EndpointGroup() {
        val GET_GUEST_MENU = endpoint(HttpMethod.Get, "/v1/guest/menu", allowedWhenSuspended = true)
        val GET_STAFF_MENU = endpoint(HttpMethod.Get, "/v1/staff/menu", allowedWhenSuspended = false)
        val CREATE_MENU_CATEGORY = endpoint(HttpMethod.Post, "/v1/staff/menu/categories", allowedWhenSuspended = false)
        val REORDER_MENU_CATEGORIES = endpoint(HttpMethod.Put, "/v1/staff/menu/categories/order", allowedWhenSuspended = false)
        val UPDATE_MENU_CATEGORY =
            endpoint(HttpMethod.Patch, "/v1/staff/menu/categories/{categoryId}", ifMatch = true, allowedWhenSuspended = false)
        val DELETE_MENU_CATEGORY =
            endpoint(HttpMethod.Delete, "/v1/staff/menu/categories/{categoryId}", ifMatch = true, allowedWhenSuspended = false)
        val REORDER_MENU_ITEMS =
            endpoint(HttpMethod.Put, "/v1/staff/menu/categories/{categoryId}/items/order", allowedWhenSuspended = false)
        val CREATE_MENU_ITEM = endpoint(HttpMethod.Post, "/v1/staff/menu/items", allowedWhenSuspended = false)
        val GET_MENU_ITEM = endpoint(HttpMethod.Get, "/v1/staff/menu/items/{itemId}", allowedWhenSuspended = false)
        val UPDATE_MENU_ITEM = endpoint(HttpMethod.Patch, "/v1/staff/menu/items/{itemId}", ifMatch = true, allowedWhenSuspended = false)
        val DELETE_MENU_ITEM = endpoint(HttpMethod.Delete, "/v1/staff/menu/items/{itemId}", ifMatch = true, allowedWhenSuspended = false)
        val SET_MENU_ITEM_SOLD_OUT = endpoint(HttpMethod.Put, "/v1/staff/menu/items/{itemId}/sold-out", allowedWhenSuspended = false)
        val PUT_MENU_ITEM_IMAGE = endpoint(HttpMethod.Put, "/v1/staff/menu/items/{itemId}/image", allowedWhenSuspended = false)
        val DELETE_MENU_ITEM_IMAGE = endpoint(HttpMethod.Delete, "/v1/staff/menu/items/{itemId}/image", allowedWhenSuspended = false)
        val GET_RESTAURANT_MENU = endpoint(HttpMethod.Get, "/v1/admin/restaurants/{restaurantId}/menu", allowedWhenSuspended = true)
    }

    object Tables : EndpointGroup() {
        val OPEN_OR_JOIN_SESSION = endpoint(HttpMethod.Post, "/v1/guest/sessions", allowedWhenSuspended = false)
        val GET_GUEST_SESSION = endpoint(HttpMethod.Get, "/v1/guest/session", allowedWhenSuspended = true)
        val CALL_WAITER = endpoint(HttpMethod.Post, "/v1/guest/waiter-calls", allowedWhenSuspended = false)
        val LIST_TABLES = endpoint(HttpMethod.Get, "/v1/staff/tables", allowedWhenSuspended = false)
        val CREATE_TABLE = endpoint(HttpMethod.Post, "/v1/staff/tables", allowedWhenSuspended = false)
        val LIST_QR_CODES = endpoint(HttpMethod.Get, "/v1/staff/tables/qr-codes", allowedWhenSuspended = false)
        val ROTATE_ALL_QR_CODES = endpoint(HttpMethod.Post, "/v1/staff/tables/qr-codes/rotate-all", allowedWhenSuspended = false)
        val GET_TABLE = endpoint(HttpMethod.Get, "/v1/staff/tables/{tableId}", allowedWhenSuspended = false)
        val UPDATE_TABLE = endpoint(HttpMethod.Patch, "/v1/staff/tables/{tableId}", ifMatch = true, allowedWhenSuspended = false)
        val DELETE_TABLE = endpoint(HttpMethod.Delete, "/v1/staff/tables/{tableId}", ifMatch = true, allowedWhenSuspended = false)
        val GET_QR_CODE = endpoint(HttpMethod.Get, "/v1/staff/tables/{tableId}/qr-code", allowedWhenSuspended = false)
        val ROTATE_QR_CODE = endpoint(HttpMethod.Post, "/v1/staff/tables/{tableId}/qr-code/rotate", allowedWhenSuspended = false)
        val OPEN_SESSION_BY_STAFF = endpoint(HttpMethod.Post, "/v1/staff/tables/{tableId}/session", allowedWhenSuspended = false)
        val GET_TABLE_SESSION = endpoint(HttpMethod.Get, "/v1/staff/table-sessions/{sessionId}", allowedWhenSuspended = false)
        val MOVE_TABLE_SESSION =
            endpoint(HttpMethod.Post, "/v1/staff/table-sessions/{sessionId}/move", ifMatch = true, allowedWhenSuspended = false)
        val LIST_WAITER_CALLS = endpoint(HttpMethod.Get, "/v1/staff/waiter-calls", allowedWhenSuspended = false)
        val ACKNOWLEDGE_WAITER_CALL = endpoint(HttpMethod.Post, "/v1/staff/waiter-calls/{callId}/acknowledge", allowedWhenSuspended = false)
    }

    object Orders : EndpointGroup() {
        val PLACE_GUEST_ORDER = endpoint(HttpMethod.Post, "/v1/guest/orders", idempotencyKey = true, allowedWhenSuspended = false)
        val LIST_GUEST_ORDERS = endpoint(HttpMethod.Get, "/v1/guest/orders", allowedWhenSuspended = true)
        val GET_GUEST_ORDER = endpoint(HttpMethod.Get, "/v1/guest/orders/{orderId}", allowedWhenSuspended = true)
        val CANCEL_GUEST_ORDER = endpoint(HttpMethod.Post, "/v1/guest/orders/{orderId}/cancel", allowedWhenSuspended = false)
        val GET_GUEST_BILL = endpoint(HttpMethod.Get, "/v1/guest/bill", allowedWhenSuspended = true)
        val PLACE_STAFF_ORDER =
            endpoint(HttpMethod.Post, "/v1/staff/table-sessions/{sessionId}/orders", idempotencyKey = true, allowedWhenSuspended = false)
        val LIST_STAFF_ORDERS = endpoint(HttpMethod.Get, "/v1/staff/orders", allowedWhenSuspended = false)
        val GET_STAFF_ORDER = endpoint(HttpMethod.Get, "/v1/staff/orders/{orderId}", allowedWhenSuspended = false)
        val CHANGE_ORDER_STATUS =
            endpoint(HttpMethod.Post, "/v1/staff/orders/{orderId}/status", ifMatch = true, allowedWhenSuspended = false)
        val REJECT_ORDER_LINE =
            endpoint(HttpMethod.Post, "/v1/staff/orders/{orderId}/lines/{lineId}/reject", ifMatch = true, allowedWhenSuspended = false)
        val GET_SESSION_BILL = endpoint(HttpMethod.Get, "/v1/staff/table-sessions/{sessionId}/bill", allowedWhenSuspended = false)
        val CLOSE_TABLE_SESSION =
            endpoint(
                HttpMethod.Post,
                "/v1/staff/table-sessions/{sessionId}/close",
                idempotencyKey = true,
                ifMatch = true,
                allowedWhenSuspended = false,
            )
        val ADJUST_CLOSED_BILL =
            endpoint(HttpMethod.Post, "/v1/staff/table-sessions/{sessionId}/bill/adjustments", ifMatch = true, allowedWhenSuspended = false)
        val LIST_CLOSED_BILLS = endpoint(HttpMethod.Get, "/v1/staff/bills", allowedWhenSuspended = false)
    }

    object Realtime : EndpointGroup() {
        val GUEST_CATCH_UP = endpoint(HttpMethod.Get, "/v1/guest/events", allowedWhenSuspended = true)
        val GUEST_REALTIME_TOKEN = endpoint(HttpMethod.Post, "/v1/guest/realtime-token", allowedWhenSuspended = false)
        val STAFF_CATCH_UP = endpoint(HttpMethod.Get, "/v1/staff/events", allowedWhenSuspended = false)
        val STAFF_REALTIME_TOKEN = endpoint(HttpMethod.Post, "/v1/staff/realtime-token", allowedWhenSuspended = false)
    }

    object Reports : EndpointGroup() {
        val DAILY_SALES_REPORT = endpoint(HttpMethod.Get, "/v1/staff/reports/daily-sales", allowedWhenSuspended = true)
    }
}
