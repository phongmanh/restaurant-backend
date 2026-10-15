package io.github.phongmanh.restaurant.restaurants.http

import io.github.phongmanh.restaurant.core.auth.application.IssuedLink
import io.github.phongmanh.restaurant.core.auth.domain.StaffMember
import io.github.phongmanh.restaurant.core.domain.Rfc3339
import io.github.phongmanh.restaurant.restaurants.domain.OpeningHours
import io.github.phongmanh.restaurant.restaurants.domain.OpeningInterval
import io.github.phongmanh.restaurant.restaurants.domain.Restaurant
import io.github.phongmanh.restaurant.restaurants.persistence.AuditEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/*
 * Request and response bodies of the restaurants feature, as `components.schemas` in the contract
 * names them. Request fields are all optional here so that a missing one is a `validation_failed`
 * field error (`required`) rather than an unreadable body.
 */

@Serializable
data class OpeningIntervalBody(
    val opens: String,
    val closes: String,
)

@Serializable
data class OwnerContactBody(
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
) {
    override fun toString(): String = "OwnerContactBody(***)"
}

/** `RestaurantUpdate`, and with [ownerContact] `AdminRestaurantUpdate`. */
@Serializable
data class RestaurantUpdateRequest(
    val name: String? = null,
    val address: String? = null,
    @SerialName("time_zone") val timeZone: String? = null,
    val languages: List<String>? = null,
    @SerialName("opening_hours") val openingHours: Map<String, List<OpeningIntervalBody>>? = null,
    @SerialName("owner_contact") val ownerContact: OwnerContactBody? = null,
)

@Serializable
data class FirstManagerBody(
    val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val phone: String? = null,
)

@Serializable
data class OnboardRestaurantRequest(
    val name: String? = null,
    val address: String? = null,
    @SerialName("time_zone") val timeZone: String? = null,
    val languages: List<String>? = null,
    @SerialName("opening_hours") val openingHours: Map<String, List<OpeningIntervalBody>>? = null,
    @SerialName("owner_contact") val ownerContact: OwnerContactBody? = null,
    @SerialName("first_manager") val firstManager: FirstManagerBody? = null,
)

@Serializable
data class SuspendRequest(
    val reason: String? = null,
)

@Serializable
data class InviteMemberRequest(
    val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val phone: String? = null,
    val role: String? = null,
)

@Serializable
data class MemberUpdateRequest(
    @SerialName("display_name") val displayName: String? = null,
    val phone: String? = null,
    val role: String? = null,
)

@Serializable
data class InviteAdminRequest(
    val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
)

/** `Restaurant`; the admin view (`AdminRestaurant`) adds the fields only admins see (§4.1). */
@Serializable
data class RestaurantResponse(
    val id: String,
    val name: String,
    val address: String,
    @SerialName("time_zone") val timeZone: String,
    val currency: String,
    val languages: List<String>,
    @SerialName("opening_hours") val openingHours: Map<String, List<OpeningIntervalBody>>,
    val status: String,
    val version: Int,
    @SerialName("owner_contact") val ownerContact: OwnerContactBody? = null,
    @SerialName("suspended_at") val suspendedAt: String? = null,
    @SerialName("suspension_reason") val suspensionReason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    companion object {
        fun staffView(r: Restaurant) =
            RestaurantResponse(
                id = r.id.toString(),
                name = r.name,
                address = r.address,
                timeZone = r.timeZone,
                currency = r.currency,
                languages = r.languages,
                openingHours = hours(r.openingHours),
                status = r.status.value,
                version = r.version,
            )

        fun adminView(r: Restaurant) =
            staffView(r).copy(
                ownerContact = r.ownerContact?.let { OwnerContactBody(it.name, it.email, it.phone) },
                suspendedAt = r.suspendedAt?.let(Rfc3339::format),
                suspensionReason = r.suspensionReason,
                createdAt = Rfc3339.format(r.createdAt),
            )

        private fun hours(h: OpeningHours) =
            h.days.mapValues { (_, intervals) -> intervals.map { OpeningIntervalBody(it.opens, it.closes) } }
    }
}

@Serializable
data class IssuedLinkResponse(
    val link: String,
    @SerialName("expires_at") val expiresAt: String,
) {
    override fun toString(): String = "IssuedLinkResponse(***)"

    companion object {
        fun of(link: IssuedLink) = IssuedLinkResponse(link.link, Rfc3339.format(link.expiresAt))
    }
}

/** `Member` (§7). */
@Serializable
data class MemberResponse(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String,
    val phone: String? = null,
    val role: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("deactivated_at") val deactivatedAt: String? = null,
    val version: Int,
) {
    companion object {
        fun of(m: StaffMember) =
            MemberResponse(
                id = m.id.toString(),
                email = m.email,
                displayName = m.displayName,
                phone = m.phone,
                role = requireNotNull(m.role).name.lowercase(Locale.ROOT),
                status = m.status.value,
                createdAt = Rfc3339.format(m.createdAt),
                deactivatedAt = m.deactivatedAt?.let(Rfc3339::format),
                version = m.version,
            )
    }
}

@Serializable
data class InvitedMemberResponse(
    val member: MemberResponse,
    val invitation: IssuedLinkResponse,
)

/** The stored copy leaves [invitation] out: a one-time secret is never kept for replays (core-and-auth §4). */
@Serializable
data class OnboardRestaurantResponse(
    val restaurant: RestaurantResponse,
    @SerialName("first_manager") val firstManager: MemberResponse,
    val invitation: IssuedLinkResponse? = null,
)

/** `AdminAccount` (§8). */
@Serializable
data class AdminAccountResponse(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
) {
    companion object {
        fun of(m: StaffMember) = AdminAccountResponse(m.id.toString(), m.email, m.displayName, m.status.value, Rfc3339.format(m.createdAt))
    }
}

@Serializable
data class InvitedAdminResponse(
    val admin: AdminAccountResponse,
    val invitation: IssuedLinkResponse,
)

/** `AuditEvent`; `AdminAuditEvent` adds [ip] and [userAgent], which the manager's view never fills (§15 Q7). */
@Serializable
data class AuditEventResponse(
    val id: String,
    @SerialName("occurred_at") val occurredAt: String,
    val action: String,
    val actor: ActorBody,
    val target: TargetBody? = null,
    @SerialName("request_id") val requestId: String? = null,
    val details: JsonObject,
    val ip: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
) {
    @Serializable
    data class ActorBody(
        val type: String,
        val id: String? = null,
        @SerialName("display_name") val displayName: String? = null,
    )

    @Serializable
    data class TargetBody(
        val type: String,
        val id: String? = null,
    )

    companion object {
        fun of(e: AuditEntry) =
            AuditEventResponse(
                id = e.id.toString(),
                occurredAt = Rfc3339.format(e.occurredAt),
                action = e.action,
                actor = ActorBody(e.actorType.value, e.actorId?.toString(), e.actorDisplayName),
                target = e.targetType?.let { TargetBody(it, e.targetId?.toString()) },
                requestId = e.requestId,
                details = e.details,
                ip = e.ip,
                userAgent = e.userAgent,
            )
    }
}

/** `StaffDataExport` (§10): personal fields only, never hashes, secrets or tokens. */
@Serializable
data class StaffDataExportResponse(
    val account: Account,
    @SerialName("audit_events") val auditEvents: List<AuditEventResponse>,
) {
    @Serializable
    data class Account(
        val id: String,
        val email: String,
        @SerialName("display_name") val displayName: String,
        val phone: String? = null,
        val role: String? = null,
        @SerialName("restaurant_id") val restaurantId: String? = null,
        val status: String,
        @SerialName("created_at") val createdAt: String,
        @SerialName("deactivated_at") val deactivatedAt: String? = null,
    )
}

@Serializable
data class EraseResultResponse(
    @SerialName("staff_id") val staffId: String,
    @SerialName("anonymised_at") val anonymisedAt: String,
    @SerialName("last_manager_removed") val lastManagerRemoved: Boolean,
)

fun OpeningIntervalBody.toDomain() = OpeningInterval(opens, closes)
