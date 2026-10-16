package io.github.phongmanh.restaurant.orders.http

import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.Tenant
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.IdempotentRoutes
import io.github.phongmanh.restaurant.core.http.JsonResponse
import io.github.phongmanh.restaurant.core.http.Page
import io.github.phongmanh.restaurant.core.http.caller
import io.github.phongmanh.restaurant.core.http.endpoint
import io.github.phongmanh.restaurant.core.http.etagOf
import io.github.phongmanh.restaurant.core.http.pageRequest
import io.github.phongmanh.restaurant.core.http.requestMeta
import io.github.phongmanh.restaurant.core.http.requireIfMatch
import io.github.phongmanh.restaurant.core.http.staffCaller
import io.github.phongmanh.restaurant.orders.application.BillManager
import io.github.phongmanh.restaurant.orders.application.BillService
import io.github.phongmanh.restaurant.orders.application.BillStaff
import io.github.phongmanh.restaurant.orders.application.LineRef
import io.github.phongmanh.restaurant.orders.application.LineRejecter
import io.github.phongmanh.restaurant.orders.application.OrderQueryService
import io.github.phongmanh.restaurant.orders.application.OrderRateLimits
import io.github.phongmanh.restaurant.orders.application.OrderStatusService
import io.github.phongmanh.restaurant.orders.application.OrderStep
import io.github.phongmanh.restaurant.orders.application.PlaceOrderService
import io.github.phongmanh.restaurant.orders.application.Placer
import io.github.phongmanh.restaurant.orders.domain.Bill
import io.github.phongmanh.restaurant.orders.domain.Order
import io.github.phongmanh.restaurant.tables.http.sessionIdParam
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The guest routes (orders §3.1, §4, §5.2). They run after the guest authentication, so the session
 * is the caller's; another session's order is `404`.
 */
class GuestOrderRoutes(
    private val placing: PlaceOrderService,
    private val queries: OrderQueryService,
    private val statuses: OrderStatusService,
    private val bills: BillService,
    private val limits: OrderRateLimits,
    private val idempotent: IdempotentRoutes,
) {
    fun install(route: Route) {
        val e = Endpoints.Orders
        route.endpoint(e.PLACE_GUEST_ORDER) {
            val guest = call.caller() as Caller.Guest
            limits.guestOrder(guest.restaurantId, guest.sessionId)
            val lines = call.receive<PlaceOrderRequest>().toLines()
            idempotent.respond(call, e.PLACE_GUEST_ORDER, guest.idempotencyScope, Tenant.Restaurant(guest.restaurantId)) {
                val order = placing.place(this, Placer.guest(guest), lines, request = null)
                JsonResponse.of(
                    HttpStatusCode.Created,
                    OrderBody.serializer(),
                    OrderBody.guest(order),
                    location = e.GET_GUEST_ORDER.pathOf("orderId" to order.id),
                    etag = etagOf(order.version),
                )
            }
        }
        route.endpoint(e.LIST_GUEST_ORDERS) {
            val page = queries.guestOrders(call.caller() as Caller.Guest, call.pageRequest())
            call.respond(Page(page.items.map(OrderBody::guest), page.nextCursor))
        }
        route.endpoint(e.GET_GUEST_ORDER) {
            respondOrder(call, queries.guestOrder(call.caller() as Caller.Guest, call.orderIdParam()), staff = false)
        }
        route.endpoint(e.CANCEL_GUEST_ORDER) {
            val guest = call.caller() as Caller.Guest
            limits.guestCancel(guest.sessionId)
            respondOrder(call, statuses.cancelByGuest(guest, call.orderIdParam()), staff = false)
        }
        route.endpoint(e.GET_GUEST_BILL) {
            call.respond(BillBody.guest(bills.guestBill(call.caller() as Caller.Guest)))
        }
    }
}

/**
 * Staff on orders (orders §3.2, §4, §5.1, §5.3). Each route makes its role scope first, before it
 * reads the body, `If-Match` or the query: 401 → 403 → 400 → 404 → 409/412 (core-and-auth §3). A
 * status change can't: its role depends on the target status, which is in the body, so the route
 * reads only that first.
 */
class StaffOrderRoutes(
    private val placing: PlaceOrderService,
    private val queries: OrderQueryService,
    private val statuses: OrderStatusService,
    private val limits: OrderRateLimits,
    private val idempotent: IdempotentRoutes,
) {
    fun install(route: Route) {
        val e = Endpoints.Orders
        route.endpoint(e.PLACE_STAFF_ORDER) {
            val staff = BillStaff.of(call.staffCaller())
            limits.staffOrder(staff.restaurantId, staff.staffId)
            val sessionId = call.sessionIdParam()
            val lines = call.receive<PlaceOrderRequest>().toLines()
            val request = call.requestMeta()
            idempotent.respond(call, e.PLACE_STAFF_ORDER, staff.idempotencyScope, Tenant.Restaurant(staff.restaurantId)) {
                val order = placing.place(this, Placer.staff(staff, sessionId), lines, request)
                JsonResponse.of(
                    HttpStatusCode.Created,
                    OrderBody.serializer(),
                    OrderBody.staff(order),
                    location = e.GET_STAFF_ORDER.pathOf("orderId" to order.id),
                    etag = etagOf(order.version),
                )
            }
        }
        route.endpoint(e.LIST_STAFF_ORDERS) {
            val caller = call.staffCaller()
            val (wanted, sessionId) = call.feedFilter()
            val page = queries.staffOrders(caller, wanted, sessionId, call.pageRequest())
            call.respond(Page(page.items.map(OrderBody::staff), page.nextCursor))
        }
        route.endpoint(e.GET_STAFF_ORDER) {
            respondOrder(call, queries.staffOrder(call.staffCaller(), call.orderIdParam()), staff = true)
        }
        route.endpoint(e.CHANGE_ORDER_STATUS) {
            val caller = call.staffCaller()
            val body = call.receive<OrderStatusChangeRequest>()
            val step = OrderStep.of(caller, body.target())
            limits.staffChange(step.staffId)
            val reason = body.reasonFor(step.to)
            val order = statuses.change(step, call.orderIdParam(), call.requireIfMatch(), reason, call.requestMeta())
            respondOrder(call, order, staff = true)
        }
        route.endpoint(e.REJECT_ORDER_LINE) {
            val rejecter = LineRejecter.of(call.staffCaller())
            limits.staffChange(rejecter.staffId)
            val reason = call.receive<RejectLineRequest>().toReason()
            val order =
                statuses.rejectLine(
                    rejecter,
                    LineRef(call.orderIdParam(), call.lineIdParam()),
                    call.requireIfMatch(),
                    reason,
                    call.requestMeta(),
                )
            respondOrder(call, order, staff = true)
        }
    }
}

/** Staff on the bill (orders §4.1, §7): reading it, the close, corrections and the day's closed bills. */
class StaffBillRoutes(
    private val bills: BillService,
    private val idempotent: IdempotentRoutes,
) {
    fun install(route: Route) {
        val e = Endpoints.Orders
        route.endpoint(e.GET_SESSION_BILL) {
            respondBill(call, bills.staffBill(call.staffCaller(), call.sessionIdParam()))
        }
        route.endpoint(e.CLOSE_TABLE_SESSION) {
            val staff = BillStaff.of(call.staffCaller())
            val sessionId = call.sessionIdParam()
            val version = call.requireIfMatch()
            val close = call.receive<CloseSessionRequest>().toClose(version)
            val request = call.requestMeta()
            idempotent.respond(call, e.CLOSE_TABLE_SESSION, staff.idempotencyScope, Tenant.Restaurant(staff.restaurantId)) {
                val bill = bills.close(this, staff, sessionId, close, request)
                JsonResponse.of(HttpStatusCode.OK, BillBody.serializer(), BillBody.staff(bill), etag = etagOf(bill.version))
            }
        }
        route.endpoint(e.ADJUST_CLOSED_BILL) {
            val manager = BillManager.of(call.staffCaller())
            val sessionId = call.sessionIdParam()
            val input = call.receive<BillAdjustmentRequest>().toAdjustment()
            respondBill(call, bills.adjust(manager, sessionId, call.requireIfMatch(), input, call.requestMeta()), HttpStatusCode.Created)
        }
        route.endpoint(e.LIST_CLOSED_BILLS) {
            val staff = BillStaff.of(call.staffCaller())
            val page = bills.closedOn(staff, call.billDate(), call.pageRequest())
            call.respond(Page(page.items.map(BillSummaryBody::of), page.nextCursor))
        }
    }

    private suspend fun respondBill(
        call: ApplicationCall,
        bill: Bill,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) {
        call.response.header(HttpHeaders.ETag, etagOf(bill.version))
        call.respond(status, BillBody.staff(bill))
    }
}

private suspend fun respondOrder(
    call: ApplicationCall,
    order: Order,
    staff: Boolean,
) {
    call.response.header(HttpHeaders.ETag, etagOf(order.version))
    call.respond(if (staff) OrderBody.staff(order) else OrderBody.guest(order))
}
