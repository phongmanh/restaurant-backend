package io.github.phongmanh.restaurant.restaurants.http

import io.github.phongmanh.restaurant.core.application.StaffRole
import io.github.phongmanh.restaurant.core.auth.domain.AccountStatus
import io.github.phongmanh.restaurant.core.auth.domain.MemberChanges
import io.github.phongmanh.restaurant.core.auth.domain.normaliseEmail
import io.github.phongmanh.restaurant.core.auth.http.Rules
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
import io.github.phongmanh.restaurant.restaurants.application.FirstManager
import io.github.phongmanh.restaurant.restaurants.application.MemberInvite
import io.github.phongmanh.restaurant.restaurants.domain.NewRestaurant
import io.github.phongmanh.restaurant.restaurants.domain.OpeningHours
import io.github.phongmanh.restaurant.restaurants.domain.OwnerContact
import io.github.phongmanh.restaurant.restaurants.domain.ProfileChanges
import io.github.phongmanh.restaurant.restaurants.domain.ProfileRules
import io.github.phongmanh.restaurant.restaurants.domain.RestaurantStatus
import io.github.phongmanh.restaurant.restaurants.domain.StaffRules
import io.github.phongmanh.restaurant.restaurants.persistence.AuditQuery
import io.ktor.server.application.ApplicationCall
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

/*
 * The field rules of restaurants §4.2, §5 and §7, applied to request bodies and query strings.
 * Every broken rule is collected, then one `400 validation_failed` lists them all.
 */

/** A profile edit (§4.2). The owner contact is read on the admin route only; elsewhere it's ignored, like any unknown field. */
fun RestaurantUpdateRequest.toChanges(admin: Boolean): ProfileChanges {
    var hours: OpeningHours? = null
    var contact: OwnerContact? = null
    validate {
        val anything = listOf(name, address, timeZone, languages, openingHours).any { it != null } || (admin && ownerContact != null)
        rule(anything, "", "empty")
        name?.let { rule(ProfileRules.text(it, ProfileRules.MAX_NAME), "/name", "invalid_length") }
        address?.let { rule(ProfileRules.text(it, ProfileRules.MAX_ADDRESS), "/address", "invalid_length") }
        timeZone?.let { rule(ProfileRules.timeZone(it), "/time_zone", "invalid_time_zone") }
        languages?.let { rule(ProfileRules.languages(it), "/languages", "invalid_languages") }
        hours = openingHours?.let { openingHours(it, this) }
        if (admin) contact = ownerContact?.let { ownerContact(it, this) }
    }
    return ProfileChanges(name, address, timeZone, languages, hours, contact)
}

/** Onboarding (§5): `time_zone` defaults to `Asia/Ho_Chi_Minh` and `languages` to `["vi"]`. */
fun OnboardRestaurantRequest.toNewRestaurant(): Pair<NewRestaurant, FirstManager> {
    var hours: OpeningHours? = null
    var contact: OwnerContact? = null
    validate {
        rule(name != null && ProfileRules.text(name, ProfileRules.MAX_NAME), "/name", if (name == null) "required" else "invalid_length")
        rule(
            address != null && ProfileRules.text(address, ProfileRules.MAX_ADDRESS),
            "/address",
            if (address == null) "required" else "invalid_length",
        )
        timeZone?.let { rule(ProfileRules.timeZone(it), "/time_zone", "invalid_time_zone") }
        languages?.let { rule(ProfileRules.languages(it), "/languages", "invalid_languages") }
        hours = openingHours?.let { openingHours(it, this) }
        contact = ownerContact?.let { ownerContact(it, this) }
        rule(firstManager != null, "/first_manager", "required")
        firstManager?.let { person(it.email, it.displayName, it.phone, "/first_manager", this) }
    }
    val manager = firstManager!!
    return NewRestaurant(
        name = name!!,
        address = address!!,
        timeZone = timeZone ?: ProfileRules.DEFAULT_TIME_ZONE,
        languages = languages ?: listOf(ProfileRules.FALLBACK_LANGUAGE),
        openingHours = hours ?: OpeningHours.NONE,
        ownerContact = contact,
    ) to FirstManager(normaliseEmail(manager.email!!), manager.displayName!!, manager.phone)
}

fun SuspendRequest.toReason(): String {
    validate {
        rule(reason != null && ProfileRules.text(reason, ProfileRules.MAX_SUSPENSION_REASON), "/reason", "invalid_length")
    }
    return reason!!
}

fun InviteMemberRequest.toInvite(): MemberInvite {
    validate {
        person(email, displayName, phone, "", this)
        rule(role != null && role(role) != null, "/role", if (role == null) "required" else "invalid_value")
    }
    return MemberInvite(normaliseEmail(email!!), displayName!!, phone, role(role!!)!!)
}

/** A member's edit (§7): at least one field. The email can't change: deactivate and invite again. */
fun MemberUpdateRequest.toChanges(): MemberChanges {
    validate {
        rule(listOf(displayName, phone, role).any { it != null }, "", "empty")
        displayName?.let { rule(ProfileRules.text(it, StaffRules.MAX_DISPLAY_NAME), "/display_name", "invalid_length") }
        phone?.let { rule(StaffRules.phone(it), "/phone", "invalid_phone") }
        role?.let { rule(role(it) != null, "/role", "invalid_value") }
    }
    return MemberChanges(displayName, phone, role?.let(::role))
}

fun InviteAdminRequest.toAdmin(): Pair<String, String> {
    validate { person(email, displayName, null, "", this) }
    return normaliseEmail(email!!) to displayName!!
}

private fun openingHours(
    days: Map<String, List<OpeningIntervalBody>>,
    rules: Rules,
): OpeningHours? {
    val (hours, errors) = OpeningHours.parse(days.mapValues { (_, list) -> list.map { it.toDomain() } }, "/opening_hours")
    rules.add(errors)
    return hours
}

private fun ownerContact(
    body: OwnerContactBody,
    rules: Rules,
): OwnerContact {
    body.name?.let { rules.rule(ProfileRules.text(it, ProfileRules.MAX_CONTACT_NAME), "/owner_contact/name", "invalid_length") }
    body.email?.let { rules.rule(StaffRules.email(normaliseEmail(it)), "/owner_contact/email", "invalid_email") }
    body.phone?.let { rules.rule(StaffRules.phone(it), "/owner_contact/phone", "invalid_phone") }
    return OwnerContact(body.name, body.email?.let(::normaliseEmail), body.phone)
}

/** The email, name and phone of someone to invite, under [pointer]. */
private fun person(
    email: String?,
    displayName: String?,
    phone: String?,
    pointer: String,
    rules: Rules,
) {
    rules.rule(
        email != null && StaffRules.email(normaliseEmail(email)),
        "$pointer/email",
        if (email ==
            null
        ) {
            "required"
        } else {
            "invalid_email"
        },
    )
    rules.rule(
        displayName != null && ProfileRules.text(displayName, StaffRules.MAX_DISPLAY_NAME),
        "$pointer/display_name",
        if (displayName == null) "required" else "invalid_length",
    )
    phone?.let { rules.rule(StaffRules.phone(it), "$pointer/phone", "invalid_phone") }
}

internal fun role(value: String): StaffRole? = StaffRole.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == value }
