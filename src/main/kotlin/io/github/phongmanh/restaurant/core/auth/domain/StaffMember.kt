package io.github.phongmanh.restaurant.core.auth.domain

import io.github.phongmanh.restaurant.core.application.StaffRole
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

/**
 * What staff management shows of a `staff_accounts` row (restaurants §7, §8): the personal and
 * status fields, never a password hash, a TOTP secret or a lock counter.
 */
data class StaffMember(
    val id: StaffId,
    /** `null` only for a platform admin. */
    val restaurantId: RestaurantId?,
    val email: String,
    val displayName: String,
    val phone: String?,
    val role: StaffRole?,
    val isPlatformAdmin: Boolean,
    val status: AccountStatus,
    val createdAt: Instant,
    val deactivatedAt: Instant?,
    val version: Int,
) {
    val isActiveManager: Boolean get() = status == AccountStatus.ACTIVE && role == StaffRole.MANAGER
}

/** A new account: a restaurant's staff member ([restaurantId] and [role] set) or a platform admin (neither). */
data class NewAccount(
    val email: String,
    val displayName: String,
    val phone: String?,
    val restaurantId: RestaurantId?,
    val role: StaffRole?,
) {
    init {
        require((restaurantId == null) == (role == null)) { "a staff member has a restaurant and a role; an admin has neither" }
    }

    val isPlatformAdmin: Boolean get() = restaurantId == null
}

/** The fields a member's edit may change (restaurants §7); `null` leaves a field as it is. */
data class MemberChanges(
    val displayName: String? = null,
    val phone: String? = null,
    val role: StaffRole? = null,
)

/** Lifetimes of the links of core-and-auth §8.5 (architecture §7). */
object LinkPolicy {
    val INVITATION = 72.hours.toJavaDuration()
    val PASSWORD_RESET = 24.hours.toJavaDuration()
}

/** What an anonymised account keeps (core-and-auth §9): its ID, so the audit log still points to it. */
object Anonymised {
    const val DISPLAY_NAME = "Deleted user"

    fun email(id: StaffId) = "deleted+$id@invalid"
}
