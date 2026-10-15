package io.github.phongmanh.restaurant.restaurants.application

import io.github.phongmanh.restaurant.core.application.Actor
import io.github.phongmanh.restaurant.core.application.AuditLog
import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.auth.application.IssuedLink
import io.github.phongmanh.restaurant.core.auth.application.PlatformAdmins
import io.github.phongmanh.restaurant.core.auth.application.StaffAccounts
import io.github.phongmanh.restaurant.core.auth.domain.AccountStatus
import io.github.phongmanh.restaurant.core.auth.domain.NewAccount
import io.github.phongmanh.restaurant.core.auth.domain.StaffMember
import io.github.phongmanh.restaurant.core.auth.domain.normaliseEmail
import io.github.phongmanh.restaurant.core.auth.persistence.LinkKind
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.restaurants.domain.StaffRules
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Platform-admin accounts (restaurants §8), in the platform transaction and the platform audit
 * chain. An admin can't act on themselves, and changes to the set of admins are serialised, so the
 * last active admin can't be deactivated even by two admins at once.
 */
class AdminAccountService(
    private val transactor: Transactor,
    private val accounts: StaffAccounts,
    private val admins: PlatformAdmins,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    suspend fun list(
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<StaffMember> = transactor.platformTransaction("admins: list") { admins.list(this, after, limit) }

    /** An invited admin and their link (72 h); they enrol TOTP when they accept it (core-and-auth §8.5). */
    suspend fun invite(
        caller: Caller.Admin,
        email: String,
        displayName: String,
        request: RequestMeta,
    ): Pair<StaffMember, IssuedLink> =
        change("admins: invite") { now ->
            val admin = accounts.create(this, NewAccount(email, displayName, phone = null, restaurantId = null, role = null), now)
            accounts.issueLink(this, admin, LinkKind.INVITATION, caller.staffId, now).let { link ->
                record(Actor.of(caller), "admin.invited", admin, request)
                admin to link
            }
        }

    /** Deactivates another admin; the last active one → `409 last_admin`. One already deactivated is returned as it is. */
    suspend fun deactivate(
        caller: Caller.Admin,
        id: StaffId,
        request: RequestMeta,
    ): StaffMember {
        requireOther(caller, id)
        return change("admins: deactivate") { now ->
            val admin = admin(id)
            if (admin.status == AccountStatus.DEACTIVATED) return@change admin
            if (admin.status == AccountStatus.ACTIVE && admins.activeCount(this) <= 1) throw DomainError.LastAdmin()
            accounts.deactivate(this, admin, now).also { record(Actor.of(caller), "admin.deactivated", admin, request) }
        }
    }

    /** A password-reset link (24 h) for another, active admin. Their TOTP secret stays (core-and-auth §8.5). */
    suspend fun issuePasswordReset(
        caller: Caller.Admin,
        id: StaffId,
        request: RequestMeta,
    ): IssuedLink {
        requireOther(caller, id)
        return change("admins: password reset") { now ->
            val admin = admin(id)
            StaffRules.requireStatus(admin, AccountStatus.ACTIVE)
            accounts.issueLink(this, admin, LinkKind.PASSWORD_RESET, caller.staffId, now).also {
                record(Actor.of(caller), "admin.password_reset_issued", admin, request)
            }
        }
    }

    /**
     * Another admin's lost authenticator: their sessions end, password and TOTP secret are cleared,
     * and a fresh invitation link lets them set both again. An invited admin just gets a new link.
     */
    suspend fun resetTotp(
        caller: Caller.Admin,
        id: StaffId,
        request: RequestMeta,
    ): IssuedLink {
        requireOther(caller, id)
        return change("admins: TOTP reset") { now -> resetTotp(admin(id), caller.staffId, Actor.of(caller), request, now) }
    }

    /** The reset itself, shared with the operator's command for the last admin (§8, architecture §7). */
    private fun TxScope.resetTotp(
        admin: StaffMember,
        createdBy: StaffId,
        actor: Actor,
        request: RequestMeta?,
        now: Instant,
    ): IssuedLink {
        if (admin.status == AccountStatus.DEACTIVATED) throw DomainError.MemberStateConflict("the admin is deactivated")
        val reset = admins.resetToInvited(this, admin, now)
        return accounts.issueLink(this, reset, LinkKind.INVITATION, createdBy, now).also {
            record(actor, "admin.totp_reset", admin, request)
        }
    }

    /**
     * The operator's commands (restaurants §5, §8; architecture §7), for when no admin can act
     * through the API. **invite**: the first admin, only while no admin is active. **reset**: the
     * TOTP of the only active admin, or of an invited one (a fresh link). The audit log names the
     * system as the actor.
     */
    suspend fun bootstrap(
        email: String,
        displayName: String?,
    ): Pair<StaffMember, IssuedLink> =
        change("admins: operator command") { now ->
            val normalised = normaliseEmail(email)
            val existing = admins.findByEmail(this, normalised)
            val others = admins.activeCount(this) - if (existing?.status == AccountStatus.ACTIVE) 1 else 0
            check(others == 0) { "another admin is active; they can do this through the API" }
            if (existing != null) return@change existing to resetTotp(existing, existing.id, Actor.SYSTEM, null, now)
            val name = requireNotNull(displayName) { "no admin has that email; give a display name to invite one" }
            val admin = accounts.create(this, NewAccount(normalised, name, phone = null, restaurantId = null, role = null), now)
            accounts.issueLink(this, admin, LinkKind.INVITATION, admin.id, now).let { link ->
                record(Actor.SYSTEM, "admin.invited", admin, request = null)
                admin to link
            }
        }

    private suspend fun <T> change(
        reason: String,
        block: suspend TxScope.(Instant) -> T,
    ): T =
        transactor.platformTransaction(reason) {
            admins.lock(this)
            block(clock.instant())
        }

    private fun TxScope.admin(id: StaffId): StaffMember = admins.find(this, id) ?: throw DomainError.NotFound()

    private fun requireOther(
        caller: Caller.Admin,
        id: StaffId,
    ) {
        if (caller.staffId == id) throw DomainError.SelfActionNotAllowed()
    }

    private fun TxScope.record(
        actor: Actor,
        action: String,
        admin: StaffMember,
        request: RequestMeta?,
    ) = audit.recordAccount(this, admin, actor, action, request)
}
