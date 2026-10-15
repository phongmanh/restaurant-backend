package io.github.phongmanh.restaurant.restaurants.http

import io.github.phongmanh.restaurant.core.domain.Rfc3339
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.Page
import io.github.phongmanh.restaurant.core.http.PageRequest
import io.github.phongmanh.restaurant.core.http.adminCaller
import io.github.phongmanh.restaurant.core.http.endpoint
import io.github.phongmanh.restaurant.core.http.pageOf
import io.github.phongmanh.restaurant.core.http.requestMeta
import io.github.phongmanh.restaurant.core.http.staffCaller
import io.github.phongmanh.restaurant.restaurants.application.AuditReadService
import io.github.phongmanh.restaurant.restaurants.application.PrivacyService
import io.github.phongmanh.restaurant.restaurants.application.RestaurantScope
import io.github.phongmanh.restaurant.restaurants.persistence.AuditEntry
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import java.time.Clock
import java.util.Locale

/** The audit log read (restaurants §9) and the privacy requests (§10). */
class AuditAndPrivacyRoutes(
    private val audit: AuditReadService,
    private val privacy: PrivacyService,
    private val clock: Clock,
) {
    fun install(route: Route) {
        with(route) {
            endpoint(Endpoints.Audit.LIST_OWN_AUDIT_EVENTS) {
                val scope = RestaurantScope.manager(call.staffCaller())
                val query = call.auditQuery(clock.instant())
                call.respond(page(audit.own(scope, query.copy(limit = query.limit + 1)), query.limit))
            }
            endpoint(Endpoints.Audit.LIST_AUDIT_EVENTS) {
                val chain = call.auditChain()
                val query = call.auditQuery(clock.instant())
                call.respond(
                    page(audit.admin(call.adminCaller(), chain, query.copy(limit = query.limit + 1), call.requestMeta()), query.limit),
                )
            }
            endpoint(Endpoints.Privacy.EXPORT_STAFF_DATA) {
                val export = privacy.export(call.adminCaller(), call.staffIdParam(), call.requestMeta())
                val a = export.account
                val account =
                    StaffDataExportResponse.Account(
                        id = a.id.toString(),
                        email = a.email,
                        displayName = a.displayName,
                        phone = a.phone,
                        role = a.role?.name?.lowercase(Locale.ROOT),
                        restaurantId = a.restaurantId?.toString(),
                        status = a.status.value,
                        createdAt = Rfc3339.format(a.createdAt),
                        deactivatedAt = a.deactivatedAt?.let(Rfc3339::format),
                    )
                call.respond(StaffDataExportResponse(account, export.auditEntries.map(AuditEventResponse::of)))
            }
            endpoint(Endpoints.Privacy.ERASE_STAFF_DATA) {
                val erased = privacy.erase(call.adminCaller(), call.staffIdParam(), call.requestMeta())
                call.respond(EraseResultResponse(erased.staffId.toString(), Rfc3339.format(erased.anonymisedAt), erased.lastManagerRemoved))
            }
        }
    }

    /** Newest first; the rows were fetched with one extra to tell whether a next page exists. */
    private fun page(
        rows: List<AuditEntry>,
        limit: Int,
    ): Page<AuditEventResponse> {
        val result = pageOf(rows, PageRequest(limit, null)) { auditSortKey(it.occurredAt, it.id) }
        return Page(result.items.map(AuditEventResponse::of), result.nextCursor)
    }
}
