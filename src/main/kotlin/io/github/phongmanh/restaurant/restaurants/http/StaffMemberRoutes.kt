package io.github.phongmanh.restaurant.restaurants.http

import io.github.phongmanh.restaurant.core.auth.domain.StaffMember
import io.github.phongmanh.restaurant.core.http.Endpoint
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.Page
import io.github.phongmanh.restaurant.core.http.adminCaller
import io.github.phongmanh.restaurant.core.http.endpoint
import io.github.phongmanh.restaurant.core.http.etagOf
import io.github.phongmanh.restaurant.core.http.pageOf
import io.github.phongmanh.restaurant.core.http.requestMeta
import io.github.phongmanh.restaurant.core.http.requireIfMatch
import io.github.phongmanh.restaurant.core.http.staffCaller
import io.github.phongmanh.restaurant.restaurants.application.RestaurantScope
import io.github.phongmanh.restaurant.restaurants.application.StaffService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * Staff management (restaurants §7): a manager's routes under `/v1/staff/members`, and the same
 * operations for platform admins under `/v1/admin/restaurants/{restaurantId}/members`. The two sets
 * differ only in how the scope is found: the manager's restaurant comes from their token, never
 * from the path.
 */
class StaffMemberRoutes(
    private val staff: StaffService,
) {
    /** The registry entries of one set of member routes, and where a new member's `Location` points. */
    private class MemberEndpoints(
        val list: Endpoint,
        val invite: Endpoint,
        val get: Endpoint,
        val update: Endpoint,
        val deactivate: Endpoint,
        val reactivate: Endpoint,
        val invitation: Endpoint,
        val passwordReset: Endpoint,
        val scope: (ApplicationCall) -> RestaurantScope,
    )

    fun install(route: Route) {
        val e = Endpoints.Staff
        route.members(
            MemberEndpoints(
                e.LIST_MEMBERS,
                e.INVITE_MEMBER,
                e.GET_MEMBER,
                e.UPDATE_MEMBER,
                e.DEACTIVATE_MEMBER,
                e.REACTIVATE_MEMBER,
                e.INVITATION_MEMBER,
                e.PASSWORD_RESET_MEMBER,
            ) { RestaurantScope.manager(it.staffCaller()) },
        )
        route.members(
            MemberEndpoints(
                e.LIST_RESTAURANT_MEMBERS,
                e.INVITE_RESTAURANT_MEMBER,
                e.GET_RESTAURANT_MEMBER,
                e.UPDATE_RESTAURANT_MEMBER,
                e.DEACTIVATE_RESTAURANT_MEMBER,
                e.REACTIVATE_RESTAURANT_MEMBER,
                e.INVITATION_RESTAURANT_MEMBER,
                e.PASSWORD_RESET_RESTAURANT_MEMBER,
            ) { RestaurantScope.admin(it.adminCaller(), it.restaurantIdParam()) },
        )
    }

    private fun Route.members(e: MemberEndpoints) {
        endpoint(e.list) {
            val scope = e.scope(call)
            val filter = call.memberFilter()
            val (page, after) = call.namePage()
            val rows = staff.list(scope, filter, after, page.limit + 1)
            val result = pageOf(rows, page) { listOf(it.displayName, it.id.toString()) }
            call.respond(Page(result.items.map(MemberResponse::of), result.nextCursor))
        }
        endpoint(e.invite) {
            val scope = e.scope(call)
            val invite = call.receive<InviteMemberRequest>().toInvite()
            val (member, link) = staff.invite(scope, invite, call.requestMeta())
            call.response.header(HttpHeaders.Location, e.get.pathOf("restaurantId" to scope.restaurantId, "staffId" to member.id))
            call.respond(HttpStatusCode.Created, InvitedMemberResponse(MemberResponse.of(member), IssuedLinkResponse.of(link)))
        }
        endpoint(e.get) {
            val scope = e.scope(call)
            respondMember(call, staff.get(scope, call.staffIdParam()))
        }
        endpoint(e.update) {
            val scope = e.scope(call)
            val changes = call.receive<MemberUpdateRequest>().toChanges()
            respondMember(call, staff.update(scope, call.staffIdParam(), call.requireIfMatch(), changes, call.requestMeta()))
        }
        endpoint(e.deactivate) {
            val scope = e.scope(call)
            call.respond(MemberResponse.of(staff.deactivate(scope, call.staffIdParam(), call.requestMeta())))
        }
        endpoint(e.reactivate) {
            val scope = e.scope(call)
            call.respond(MemberResponse.of(staff.reactivate(scope, call.staffIdParam(), call.requestMeta())))
        }
        endpoint(e.invitation) {
            val scope = e.scope(call)
            val link = staff.reissueInvitation(scope, call.staffIdParam(), call.requestMeta())
            call.respond(HttpStatusCode.Created, IssuedLinkResponse.of(link))
        }
        endpoint(e.passwordReset) {
            val scope = e.scope(call)
            val link = staff.issuePasswordReset(scope, call.staffIdParam(), call.requestMeta())
            call.respond(HttpStatusCode.Created, IssuedLinkResponse.of(link))
        }
    }

    private suspend fun respondMember(
        call: ApplicationCall,
        member: StaffMember,
    ) {
        call.response.header(HttpHeaders.ETag, etagOf(member.version))
        call.respond(MemberResponse.of(member))
    }
}
