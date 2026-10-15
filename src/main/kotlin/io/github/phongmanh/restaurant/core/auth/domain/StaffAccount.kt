package io.github.phongmanh.restaurant.core.auth.domain

import io.github.phongmanh.restaurant.core.application.StaffRole
import io.github.phongmanh.restaurant.core.application.Tenant
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import java.time.Instant
import java.util.Locale
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

enum class AccountStatus {
    INVITED,
    ACTIVE,
    DEACTIVATED,
    ;

    val value: String get() = name.lowercase(Locale.ROOT)

    companion object {
        fun of(value: String): AccountStatus = valueOf(value.uppercase(Locale.ROOT))
    }
}

/** What sign-in needs of a `staff_accounts` row (core-and-auth §9). */
class StaffAccount(
    val id: StaffId,
    val email: String,
    val displayName: String,
    /** `null` only for a platform admin. */
    val restaurantId: RestaurantId?,
    val role: StaffRole?,
    val isPlatformAdmin: Boolean,
    val status: AccountStatus,
    val passwordHash: String?,
    val totpSecret: ByteArray?,
    val totpLastStep: Long?,
    val lockedUntil: Instant?,
) {
    fun isLocked(now: Instant): Boolean = lockedUntil != null && lockedUntil.isAfter(now)

    /**
     * The transaction that records this account's events: its restaurant's, so the entries join that
     * restaurant's audit chain, or the platform's for an admin (§12).
     */
    val tenant: Tenant
        get() = restaurantId?.let { Tenant.Restaurant(it) } ?: Tenant.Platform("auth: platform admin ${id.value}")
}

/**
 * The per-account lock (architecture §7, §8.1): 10 failed passwords or TOTP codes within 15 min
 * lock the account for 15 min. It lives in PostgreSQL, so it holds when Redis is down.
 */
object LockoutPolicy {
    const val MAX_FAILURES = 10
    val WINDOW = 15.minutes.toJavaDuration()
    val LOCK = 15.minutes.toJavaDuration()
}

/** Lifetimes from architecture §7 and §8 (owner, 2026-10-15). */
object SessionPolicy {
    /** A sign-in lasts 24 h, however often it's refreshed. */
    val SESSION = 24.hours.toJavaDuration()

    /** A refresh token expires 12 h after its issue if it isn't used. */
    val REFRESH_IDLE = 12.hours.toJavaDuration()

    /** The admin's MFA step has 5 min. */
    val MFA_CHALLENGE = 5.minutes.toJavaDuration()
}
