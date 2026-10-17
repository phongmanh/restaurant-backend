package io.github.phongmanh.restaurant.core.auth.application

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.application.requireVersionedUpdate
import io.github.phongmanh.restaurant.core.auth.LinkUrls
import io.github.phongmanh.restaurant.core.auth.crypto.SecureTokens
import io.github.phongmanh.restaurant.core.auth.domain.LinkPolicy
import io.github.phongmanh.restaurant.core.auth.domain.MemberChanges
import io.github.phongmanh.restaurant.core.auth.domain.NewAccount
import io.github.phongmanh.restaurant.core.auth.domain.StaffMember
import io.github.phongmanh.restaurant.core.auth.persistence.LinkKind
import io.github.phongmanh.restaurant.core.auth.persistence.LinkTokenRepository
import io.github.phongmanh.restaurant.core.auth.persistence.MemberFilter
import io.github.phongmanh.restaurant.core.auth.persistence.NewLink
import io.github.phongmanh.restaurant.core.auth.persistence.PlatformAdminRepository
import io.github.phongmanh.restaurant.core.auth.persistence.RevokeReason
import io.github.phongmanh.restaurant.core.auth.persistence.SeatCounts
import io.github.phongmanh.restaurant.core.auth.persistence.StaffMemberRepository
import io.github.phongmanh.restaurant.core.auth.persistence.StaffSessionRepository
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.IdGenerator
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.persistence.advisoryLock
import java.time.Instant
import java.util.UUID

/** A link just issued: shown once, never stored in plain text and never logged (core-and-auth §8.5). */
class IssuedLink(
    val link: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "IssuedLink(***)"
}

/**
 * The `StaffAccounts` port (core-and-auth §14) through which the restaurants feature manages
 * accounts. Every method runs in the caller's transaction, and every method on a restaurant's staff
 * takes the restaurant's ID and filters by it. The rules (who may do what, the last manager) are the
 * restaurants feature's; this keeps what follows from the account model: a role change or a
 * deactivation ends the person's sessions (§8.3), and a new link replaces the earlier one (§8.5).
 */
class StaffAccounts(
    private val members: StaffMemberRepository,
    private val admins: PlatformAdminRepository,
    private val sessions: StaffSessionRepository,
    private val links: LinkTokenRepository,
    private val tokens: SecureTokens,
    private val urls: LinkUrls,
    private val ids: IdGenerator,
) {
    /** Serialises changes to [restaurantId]'s staff until the transaction ends (restaurants §7). */
    fun lockStaffOf(
        tx: TxScope,
        restaurantId: RestaurantId,
    ) = advisoryLock(tx, "staff:$restaurantId")

    fun list(
        tx: TxScope,
        restaurantId: RestaurantId,
        filter: MemberFilter,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<StaffMember> = members.list(tx, restaurantId, filter, after, limit)

    fun find(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: StaffId,
    ): StaffMember? = members.find(tx, restaurantId, id)

    /** The restaurant's seats (invited and active accounts) and active managers. */
    fun counts(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): SeatCounts = members.counts(tx, restaurantId)

    /** A new `invited` account. An email that already has an account, anywhere → `409 email_in_use`. */
    fun create(
        tx: TxScope,
        account: NewAccount,
        now: Instant,
    ): StaffMember = members.insert(tx, StaffId(ids.next()), account, now) ?: throw DomainError.EmailInUse()

    /** Applies [changes] if [version] is current (404 or 412 otherwise); a role change ends the member's sessions. */
    fun update(
        tx: TxScope,
        member: StaffMember,
        version: Int,
        changes: MemberChanges,
        now: Instant,
    ): StaffMember {
        val restaurantId = requireNotNull(member.restaurantId) { "an admin isn't a restaurant member" }
        requireVersionedUpdate(members.update(tx, member, version, changes, now)) { members.find(tx, restaurantId, member.id) != null }
        if (changes.role != null && changes.role != member.role) sessions.revokeAll(tx, member.id, RevokeReason.ROLE_CHANGED, now)
        return reread(tx, member)
    }

    /** Deactivates the account, ends its sessions and voids its open links. */
    fun deactivate(
        tx: TxScope,
        member: StaffMember,
        now: Instant,
    ): StaffMember {
        members.deactivate(tx, member, now)
        sessions.revokeAll(tx, member.id, RevokeReason.DEACTIVATED, now)
        links.supersedeAll(tx, member.id, now)
        return reread(tx, member)
    }

    /** Back to `active`, or `invited` if the person never accepted their invitation. */
    fun reactivate(
        tx: TxScope,
        member: StaffMember,
        now: Instant,
    ): StaffMember {
        members.reactivate(tx, member, now)
        return reread(tx, member)
    }

    /** A new invitation (72 h) or password-reset (24 h) link for [member]; the earlier one of that kind stops working. */
    fun issueLink(
        tx: TxScope,
        member: StaffMember,
        kind: LinkKind,
        createdBy: StaffId,
        now: Instant,
    ): IssuedLink {
        val token = tokens.generate()
        val expiresAt = now.plus(if (kind == LinkKind.INVITATION) LinkPolicy.INVITATION else LinkPolicy.PASSWORD_RESET)
        links.issue(tx, NewLink(SecureTokens.hash(token), member.id, kind, createdBy, now, expiresAt))
        return IssuedLink(urls.of(kind, member.isPlatformAdmin, token), expiresAt)
    }

    private fun reread(
        tx: TxScope,
        member: StaffMember,
    ): StaffMember =
        member.restaurantId?.let { members.find(tx, it, member.id) }
            ?: admins.find(tx, member.id)
            ?: error("the account vanished in its own transaction")
}

/** The platform-admin side of the port (restaurants §8). Admins belong to no restaurant. */
class PlatformAdmins(
    private val admins: PlatformAdminRepository,
    private val sessions: StaffSessionRepository,
    private val links: LinkTokenRepository,
) {
    /** Serialises changes to the set of admins until the transaction ends (the last-admin rule). */
    fun lock(tx: TxScope) = advisoryLock(tx, "platform_admins")

    fun list(
        tx: TxScope,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<StaffMember> = admins.list(tx, after, limit)

    fun find(
        tx: TxScope,
        id: StaffId,
    ): StaffMember? = admins.find(tx, id)

    fun findByEmail(
        tx: TxScope,
        email: String,
    ): StaffMember? = admins.findByEmail(tx, email)

    fun activeCount(tx: TxScope): Int = admins.countActive(tx)

    /**
     * A lost authenticator: sessions end, password and TOTP secret are cleared, open links are voided
     * (a reset link would otherwise work again once the admin is active), and the admin is `invited` again.
     */
    fun resetToInvited(
        tx: TxScope,
        admin: StaffMember,
        now: Instant,
    ): StaffMember {
        sessions.revokeAll(tx, admin.id, RevokeReason.TOTP_RESET, now)
        links.supersedeAll(tx, admin.id, now)
        admins.resetToInvited(tx, admin.id, now)
        return admins.find(tx, admin.id) ?: error("the account vanished in its own transaction")
    }
}

/** Privacy requests about a restaurant's staff member (restaurants §10, NFR-PRIV-01). */
class StaffPrivacy(
    private val members: StaffMemberRepository,
    private val sessions: StaffSessionRepository,
    private val links: LinkTokenRepository,
) {
    /** A restaurant's staff member, whichever restaurant; never a platform admin or an anonymised account. */
    fun find(
        tx: TxScope,
        id: StaffId,
    ): StaffMember? = members.findRestaurantStaff(tx, id)

    /** Deactivates and anonymises the account now, as the retention job would after 30 days (core-and-auth §9). */
    fun erase(
        tx: TxScope,
        member: StaffMember,
        now: Instant,
    ) {
        sessions.revokeAll(tx, member.id, RevokeReason.DEACTIVATED, now)
        links.supersedeAll(tx, member.id, now)
        members.anonymise(tx, member, now)
    }
}
