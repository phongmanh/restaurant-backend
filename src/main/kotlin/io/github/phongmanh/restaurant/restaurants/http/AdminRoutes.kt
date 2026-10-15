package io.github.phongmanh.restaurant.restaurants.http

import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.StoredResponse
import io.github.phongmanh.restaurant.core.application.Tenant
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.http.ApiJson
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.IdempotentRoutes
import io.github.phongmanh.restaurant.core.http.JsonResponse
import io.github.phongmanh.restaurant.core.http.Page
import io.github.phongmanh.restaurant.core.http.adminCaller
import io.github.phongmanh.restaurant.core.http.endpoint
import io.github.phongmanh.restaurant.core.http.etagOf
import io.github.phongmanh.restaurant.core.http.pageOf
import io.github.phongmanh.restaurant.core.http.requestMeta
import io.github.phongmanh.restaurant.core.http.requireIfMatch
import io.github.phongmanh.restaurant.restaurants.application.AdminAccountService
import io.github.phongmanh.restaurant.restaurants.application.OnboardingService
import io.github.phongmanh.restaurant.restaurants.application.RestaurantScope
import io.github.phongmanh.restaurant.restaurants.application.RestaurantService
import io.github.phongmanh.restaurant.restaurants.application.SuspensionService
import io.github.phongmanh.restaurant.restaurants.domain.Restaurant
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import java.util.UUID

/** `/v1/admin/restaurants` and `/v1/admin/admins` (restaurants §4–6, §8), for platform admins only. */
class AdminRoutes(
    private val restaurants: RestaurantService,
    private val onboarding: OnboardingService,
    private val suspension: SuspensionService,
    private val admins: AdminAccountService,
    private val idempotent: IdempotentRoutes,
) {
    fun install(route: Route) {
        route.restaurantRoutes()
        route.accountRoutes()
    }

    private fun Route.restaurantRoutes() {
        val e = Endpoints.Admin
        endpoint(e.LIST_RESTAURANTS) {
            val (status, prefix) = call.restaurantFilter()
            val (page, after) = call.namePage()
            val result = pageOf(restaurants.list(status, prefix, after, page.limit + 1), page) { listOf(it.name, it.id.toString()) }
            call.respond(Page(result.items.map(RestaurantResponse::adminView), result.nextCursor))
        }
        endpoint(e.ONBOARD_RESTAURANT) { onboard(call) }
        endpoint(e.GET_RESTAURANT) { respondRestaurant(call, restaurants.get(call.restaurantIdParam())) }
        endpoint(e.UPDATE_RESTAURANT) {
            val scope = RestaurantScope.admin(call.adminCaller(), call.restaurantIdParam())
            val changes = call.receive<RestaurantUpdateRequest>().toChanges(admin = true)
            respondRestaurant(call, restaurants.update(scope, call.requireIfMatch(), changes, call.requestMeta()))
        }
        endpoint(e.SUSPEND_RESTAURANT) {
            val id = call.restaurantIdParam()
            val reason = call.receive<SuspendRequest>().toReason()
            call.respond(RestaurantResponse.adminView(suspension.suspend(call.adminCaller(), id, reason, call.requestMeta())))
        }
        endpoint(e.REACTIVATE_RESTAURANT) {
            val restaurant = suspension.reactivate(call.adminCaller(), call.restaurantIdParam(), call.requestMeta())
            call.respond(RestaurantResponse.adminView(restaurant))
        }
    }

    private fun Route.accountRoutes() {
        val e = Endpoints.Admin
        endpoint(e.LIST_ADMINS) {
            val (page, after) = call.namePage()
            val result = pageOf(admins.list(after, page.limit + 1), page) { listOf(it.displayName, it.id.toString()) }
            call.respond(Page(result.items.map(AdminAccountResponse::of), result.nextCursor))
        }
        endpoint(e.INVITE_ADMIN) {
            val (email, name) = call.receive<InviteAdminRequest>().toAdmin()
            val (admin, link) = admins.invite(call.adminCaller(), email, name, call.requestMeta())
            call.respond(HttpStatusCode.Created, InvitedAdminResponse(AdminAccountResponse.of(admin), IssuedLinkResponse.of(link)))
        }
        endpoint(e.DEACTIVATE_ADMIN) {
            call.respond(AdminAccountResponse.of(admins.deactivate(call.adminCaller(), call.staffIdParam(), call.requestMeta())))
        }
        endpoint(e.PASSWORD_RESET_ADMIN) {
            val link = admins.issuePasswordReset(call.adminCaller(), call.staffIdParam(), call.requestMeta())
            call.respond(HttpStatusCode.Created, IssuedLinkResponse.of(link))
        }
        endpoint(e.TOTP_RESET_ADMIN) {
            val link = admins.resetTotp(call.adminCaller(), call.staffIdParam(), call.requestMeta())
            call.respond(HttpStatusCode.Created, IssuedLinkResponse.of(link))
        }
    }

    /**
     * Onboarding under its `Idempotency-Key` (§5). The stored answer leaves the invitation out, and
     * a replay issues a fresh one, which replaces the first (core-and-auth §4).
     */
    private suspend fun onboard(call: RoutingCall) {
        val caller = call.adminCaller()
        val request = call.requestMeta()
        val (restaurant, manager) = call.receive<OnboardRestaurantRequest>().toNewRestaurant()
        idempotent.respond(
            call,
            Endpoints.Admin.ONBOARD_RESTAURANT,
            caller.idempotencyScope,
            Tenant.Platform("restaurants: onboarding"),
            reissue = { stored -> replay(caller, stored, request) },
        ) {
            val done = onboarding.onboard(caller, restaurant, manager, request)
            val answer = OnboardRestaurantResponse(RestaurantResponse.adminView(done.restaurant), MemberResponse.of(done.firstManager))
            onboarded(answer, IssuedLinkResponse.of(done.invitation))
        }
    }

    private suspend fun replay(
        caller: Caller.Admin,
        stored: StoredResponse,
        request: RequestMeta,
    ): JsonResponse {
        val answer = ApiJson.decodeFromString(OnboardRestaurantResponse.serializer(), String(stored.body))
        val restaurantId = RestaurantId(UUID.fromString(answer.restaurant.id))
        val link = onboarding.reissueInvitation(caller, restaurantId, StaffId(UUID.fromString(answer.firstManager.id)), request)
        return onboarded(answer, IssuedLinkResponse.of(link))
    }

    /** `201` with the link; the copy kept for replays has none. */
    private fun onboarded(
        answer: OnboardRestaurantResponse,
        invitation: IssuedLinkResponse,
    ): JsonResponse {
        val serializer = OnboardRestaurantResponse.serializer()
        return JsonResponse(
            status = HttpStatusCode.Created,
            body = ApiJson.encodeToString(serializer, answer.copy(invitation = invitation)).toByteArray(),
            location = Endpoints.Admin.GET_RESTAURANT.pathOf("restaurantId" to answer.restaurant.id),
            storedBody = ApiJson.encodeToString(serializer, answer.copy(invitation = null)).toByteArray(),
        )
    }

    private suspend fun respondRestaurant(
        call: ApplicationCall,
        restaurant: Restaurant,
    ) {
        call.response.header(HttpHeaders.ETag, etagOf(restaurant.version))
        call.respond(RestaurantResponse.adminView(restaurant))
    }
}
