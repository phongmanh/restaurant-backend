package io.github.phongmanh.restaurant.restaurants.http

import io.github.phongmanh.restaurant.core.auth.domain.AccountStatus
import io.github.phongmanh.restaurant.core.auth.http.Rules.Companion.validate
import io.github.phongmanh.restaurant.core.auth.persistence.MemberFilter
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.FieldError
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.Rfc3339
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.http.PageRequest
import io.github.phongmanh.restaurant.core.http.pageRequest
import io.github.phongmanh.restaurant.restaurants.application.AuditChain
import io.github.phongmanh.restaurant.restaurants.domain.ProfileRules
import io.github.phongmanh.restaurant.restaurants.domain.RestaurantStatus
import io.github.phongmanh.restaurant.restaurants.persistence.AuditQuery
import io.ktor.server.application.ApplicationCall
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

/*
 * Query strings and path parameters of the restaurants feature (restaurants §7, §9, §11). A bad
 * filter is `400 validation_failed`; a path ID that isn't a UUID can't name anything, so it's `404`.
 */

/** `?status=` and `?role=` of the member lists (§7). */
fun ApplicationCall.memberFilter(): MemberFilter {
    val status = request.queryParameters["status"]
    val role = request.queryParameters["role"]
    val parsedStatus = status?.let { s -> AccountStatus.entries.firstOrNull { it.value == s } }
    validate {
        rule(status == null || parsedStatus != null, "query.status", "invalid_value")
        rule(role == null || role(role) != null, "query.role", "invalid_value")
    }
    return MemberFilter(parsedStatus, role?.let(::role))
}

/** `?status=` and `?q=` (a name prefix) of the admins' restaurant list. */
fun ApplicationCall.restaurantFilter(): Pair<RestaurantStatus?, String?> {
    val status = request.queryParameters["status"]
    val q = request.queryParameters["q"]
    val parsed = status?.let { s -> RestaurantStatus.entries.firstOrNull { it.value == s } }
    validate {
        rule(status == null || parsed != null, "query.status", "invalid_value")
        rule(q == null || ProfileRules.text(q, ProfileRules.MAX_NAME), "query.q", "invalid_length")
    }
    return parsed to q
}

/** The page asked for, with the cursor read as (text, ID): the sort key of every list here except the audit log. */
fun ApplicationCall.namePage(): Pair<PageRequest, Pair<String, UUID>?> {
    val page = pageRequest()
    return page to page.after?.let { sortKey(it) { name, id -> uuid(id)?.let { name to it } } }
}

/** The filters of an audit read (§9): the last 7 days by default, at most 92 days per query. */
fun ApplicationCall.auditQuery(now: Instant): AuditQuery {
    val page = pageRequest()
    val params = request.queryParameters
    val to = params["to"]?.let(::instant)
    val from = params["from"]?.let(::instant)
    val actor = params["actor_id"]?.let(::uuid)
    val target = params["target_id"]?.let(::uuid)
    validate {
        rule(params["to"] == null || to != null, "query.to", "invalid_time")
        rule(params["from"] == null || from != null, "query.from", "invalid_time")
        rule((params["action"]?.length ?: 0) <= MAX_ACTION, "query.action", "too_long")
        rule(params["actor_id"] == null || actor != null, "query.actor_id", "invalid_id")
        rule(params["target_id"] == null || target != null, "query.target_id", "invalid_id")
    }
    val end = to ?: now
    val start = from ?: end.minus(DEFAULT_AUDIT_WINDOW)
    validate { rule(!start.isAfter(end) && Duration.between(start, end) <= MAX_AUDIT_WINDOW, "query.from", "out_of_range") }
    val after = page.after?.let { sortKey(it) { at, id -> instant(at)?.let { t -> uuid(id)?.let { t to it } } } }
    return AuditQuery(start, end, params["action"], actor, target, after, page.limit)
}

/** `?restaurant_id=` of the admins' audit read: a restaurant's ID, or `platform`. Required. */
fun ApplicationCall.auditChain(): AuditChain {
    val value = request.queryParameters["restaurant_id"]
    val chain = if (value == PLATFORM_CHAIN) AuditChain.Platform else value?.let(::uuid)?.let { AuditChain.Of(RestaurantId(it)) }
    return chain
        ?: throw DomainError.ValidationFailed(listOf(FieldError("query.restaurant_id", "invalid_value", "a restaurant ID or platform")))
}

/** A path parameter that names a staff member; one that isn't a UUID can't name anything → 404. */
fun ApplicationCall.staffIdParam(): StaffId = StaffId(parameters["staffId"]?.let(::uuid) ?: throw DomainError.NotFound())

fun ApplicationCall.restaurantIdParam(): RestaurantId =
    RestaurantId(parameters["restaurantId"]?.let(::uuid) ?: throw DomainError.NotFound())

private fun uuid(value: String): UUID? =
    runCatching { UUID.fromString(value) }.getOrNull()?.takeIf {
        it.toString() ==
            value.lowercase(Locale.ROOT)
    }

private fun instant(value: String): Instant? = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()

private fun badCursor(): Nothing =
    throw DomainError.ValidationFailed(listOf(FieldError("query.cursor", "invalid", "use next_cursor from the previous page")))

/** A cursor's sort key of two parts, read by [read]; anything else → `400` on `query.cursor`. */
private fun <T> sortKey(
    key: List<String>,
    read: (String, String) -> T?,
): T = key.takeIf { it.size == 2 }?.let { (first, second) -> read(first, second) } ?: badCursor()

/** The sort key of an audit entry for its cursor. */
fun auditSortKey(
    at: Instant,
    id: UUID,
) = listOf(Rfc3339.format(at), id.toString())

private const val MAX_ACTION = 64
private const val PLATFORM_CHAIN = "platform"
private const val DEFAULT_AUDIT_DAYS = 7L
private const val MAX_AUDIT_DAYS = 92L
private val DEFAULT_AUDIT_WINDOW = Duration.ofDays(DEFAULT_AUDIT_DAYS)
private val MAX_AUDIT_WINDOW = Duration.ofDays(MAX_AUDIT_DAYS)
