package io.github.phongmanh.restaurant.restaurants.domain

import io.github.phongmanh.restaurant.core.application.StaffRole
import io.github.phongmanh.restaurant.core.auth.domain.AccountStatus
import io.github.phongmanh.restaurant.core.auth.domain.StaffMember
import io.github.phongmanh.restaurant.core.domain.DomainError

/**
 * Who changes a restaurant's staff (restaurants §3, §7): one of its managers, or a platform admin.
 * Some rules bind only managers.
 */
enum class StaffActor { MANAGER, PLATFORM_ADMIN }

/** The rules of restaurants §7 that don't need the database. */
object StaffRules {
    /** Invited + active accounts per restaurant (§15 Q6, ASSUMPTION-12). */
    const val MAX_SEATS = 100

    const val MAX_EMAIL = 254
    const val MAX_DISPLAY_NAME = 100
    private val PHONE = Regex("\\+[1-9][0-9]{6,14}")

    /**
     * A plausible email address of at most 254 characters: one `@`, something on each side, a dot in
     * the domain, no spaces or control characters. Whether it reaches anyone is the inviter's matter.
     */
    fun email(value: String): Boolean {
        val at = value.indexOf('@')
        return value.length <= MAX_EMAIL &&
            at > 0 &&
            at == value.lastIndexOf('@') &&
            '.' in value.substring(at + 1).trim('.') &&
            value.none { it.isWhitespace() || it.isISOControl() }
    }

    /** E.164, as the contract's `Phone` pattern. */
    fun phone(value: String): Boolean = PHONE.matches(value)

    /**
     * The last-manager rule: a manager can't demote or deactivate the restaurant's last active
     * manager, themselves included. [activeManagers] is counted under the staff lock. Platform admins
     * aren't bound (a support case); their change is audited.
     */
    fun checkLastManager(
        actor: StaffActor,
        member: StaffMember,
        activeManagers: Int,
    ) {
        if (actor == StaffActor.MANAGER && member.isActiveManager && activeManagers <= 1) throw DomainError.LastManager()
    }

    /** Architecture §7, §15 Q3: a manager issues reset links for waiters and kitchen only; an admin for anyone. */
    fun checkMayReset(
        actor: StaffActor,
        member: StaffMember,
    ) {
        if (actor == StaffActor.MANAGER && member.role == StaffRole.MANAGER) throw DomainError.Forbidden()
    }

    /** An invitation is re-issued only for an invited member, a reset only for an active one. */
    fun requireStatus(
        member: StaffMember,
        status: AccountStatus,
    ) {
        if (member.status != status) throw DomainError.MemberStateConflict("the member is ${member.status.value}, not ${status.value}")
    }
}
