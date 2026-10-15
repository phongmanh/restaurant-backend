package io.github.phongmanh.restaurant.core.auth.application

import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.StaffRole
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.inTransaction
import io.github.phongmanh.restaurant.core.auth.crypto.Base32
import io.github.phongmanh.restaurant.core.auth.crypto.PasswordHashing
import io.github.phongmanh.restaurant.core.auth.crypto.SecretBox
import io.github.phongmanh.restaurant.core.auth.crypto.SecureTokens
import io.github.phongmanh.restaurant.core.auth.crypto.Totp
import io.github.phongmanh.restaurant.core.auth.domain.AccountStatus
import io.github.phongmanh.restaurant.core.auth.domain.PasswordPolicy
import io.github.phongmanh.restaurant.core.auth.domain.StaffAccount
import io.github.phongmanh.restaurant.core.auth.persistence.CallerRepository
import io.github.phongmanh.restaurant.core.auth.persistence.LinkKind
import io.github.phongmanh.restaurant.core.auth.persistence.LinkTokenRepository
import io.github.phongmanh.restaurant.core.auth.persistence.RevokeReason
import io.github.phongmanh.restaurant.core.auth.persistence.StaffAccountRepository
import io.github.phongmanh.restaurant.core.auth.persistence.StaffSessionRepository
import io.github.phongmanh.restaurant.core.auth.persistence.StaffTotpRepository
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.FieldError
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom
import java.time.Clock
import java.util.Locale

/** What `POST /v1/auth/links/inspect` shows: enough to greet the person (§8.5). */
data class LinkDetails(
    val kind: LinkKind,
    val email: String,
    val displayName: String,
    val restaurantName: String?,
    val role: StaffRole?,
    val totpEnrolmentRequired: Boolean,
)

sealed interface AcceptResult {
    /** The account is active; the app signs in next. */
    data object Done : AcceptResult

    /** An admin's first call: the secret to scan, then the same call again with a code (§8.5). */
    class TotpSetup(
        val secret: String,
        val otpauthUri: String,
    ) : AcceptResult {
        override fun toString(): String = "TotpSetup(***)"
    }
}

/**
 * Invitation and password-reset links (core-and-auth §8.5). The restaurants feature issues them;
 * here they're inspected and used. A bad link, whatever is wrong with it, is `404 link_invalid`.
 */
class LinkService(
    private val transactor: Transactor,
    private val accounts: StaffAccountRepository,
    private val totp: StaffTotpRepository,
    private val sessions: StaffSessionRepository,
    private val links: LinkTokenRepository,
    private val callers: CallerRepository,
    private val policy: PasswordPolicy,
    private val hashing: PasswordHashing,
    private val totpKeys: SecretBox,
    private val audit: AuthAudit,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) {
    suspend fun inspect(token: String): LinkDetails =
        transactor.globalTransaction {
            val link = links.findValid(this, SecureTokens.hash(token), clock.instant()) ?: throw DomainError.LinkInvalid()
            val account =
                accounts.findById(this, link.staffId)?.takeIf { it.status == expectedStatus(link.kind) } ?: throw DomainError.LinkInvalid()
            LinkDetails(
                kind = link.kind,
                email = account.email,
                displayName = account.displayName,
                restaurantName = account.restaurantId?.let { callers.restaurantName(this, it) },
                role = account.role,
                totpEnrolmentRequired = account.isPlatformAdmin && link.kind == LinkKind.INVITATION,
            )
        }

    /** Staff: one call. Admin: a first call without [totpCode] for the secret, then a second with it. */
    suspend fun acceptInvitation(
        token: String,
        password: String,
        totpCode: String?,
        request: RequestMeta,
    ): AcceptResult {
        val account = account(token, LinkKind.INVITATION)
        val normalised = policy.check(password, account.email, "/password")
        return when {
            !account.isPlatformAdmin -> {
                activate(token, account, hashing.hash(normalised), enrolled = null, request)
                AcceptResult.Done
            }

            totpCode == null -> {
                startEnrolment(token, account)
            }

            else -> {
                activate(token, account, hashing.hash(normalised), enrolledTotp(account, totpCode), request)
                AcceptResult.Done
            }
        }
    }

    /** The pending secret and the step its first code used; a wrong code is `400` and keeps the link (§8.5). */
    private suspend fun enrolledTotp(
        account: StaffAccount,
        code: String,
    ): Pair<ByteArray, Long> {
        val sealed = transactor.platformTransaction("auth: admin TOTP enrolment") { totp.pendingTotp(this, account.id) }
        val step = sealed?.let { Totp.verify(totpKeys.open(it, MfaService.aad(account)), code, clock.instant().epochSecond, null) }
        if (sealed == null || step == null) {
            throw DomainError.ValidationFailed(listOf(FieldError("/totp_code", "invalid", "the code doesn't match the secret")))
        }
        return sealed to step
    }

    /** Sets a new password, clears the lock, and ends every session of the person (§8.5). */
    suspend fun completePasswordReset(
        token: String,
        password: String,
        request: RequestMeta,
    ) {
        val account = account(token, LinkKind.PASSWORD_RESET)
        val hash = hashing.hash(policy.check(password, account.email, "/password"))
        val now = clock.instant()
        transactor.inTransaction(account.tenant) {
            if (!links.use(this, SecureTokens.hash(token), now)) throw DomainError.LinkInvalid()
            accounts.setPassword(this, account.id, hash, now)
            sessions.revokeAll(this, account.id, RevokeReason.PASSWORD_RESET, now)
            audit.record(this, account, "auth.password_reset.completed", request)
        }
    }

    private suspend fun account(
        token: String,
        kind: LinkKind,
    ): StaffAccount =
        transactor.globalTransaction {
            val link =
                links.findValid(this, SecureTokens.hash(token), clock.instant())?.takeIf { it.kind == kind }
                    ?: throw DomainError.LinkInvalid()
            accounts.findById(this, link.staffId)?.takeIf { it.status == expectedStatus(kind) } ?: throw DomainError.LinkInvalid()
        }

    private suspend fun startEnrolment(
        token: String,
        account: StaffAccount,
    ): AcceptResult.TotpSetup {
        val secret = Totp.newSecret(random)
        transactor.platformTransaction("auth: admin TOTP enrolment") {
            // The link isn't used up yet, so a person who closes the page can start again.
            links.findValid(this, SecureTokens.hash(token), clock.instant()) ?: throw DomainError.LinkInvalid()
            totp.setPendingTotp(this, account.id, totpKeys.seal(secret, MfaService.aad(account)), clock.instant())
        }
        return AcceptResult.TotpSetup(
            secret = Base32.encode(secret),
            otpauthUri = Totp.otpauthUri(TOTP_ISSUER, account.email, secret),
        )
    }

    private suspend fun activate(
        token: String,
        account: StaffAccount,
        passwordHash: String,
        enrolled: Pair<ByteArray, Long>?,
        request: RequestMeta,
    ) {
        val now = clock.instant()
        transactor.inTransaction(account.tenant) {
            val activated =
                links.use(this, SecureTokens.hash(token), now) && accounts.activate(this, account.id, passwordHash, enrolled, now)
            if (!activated) throw DomainError.LinkInvalid()
            val role = account.role?.name?.lowercase(Locale.ROOT) ?: "platform_admin"
            audit.record(this, account, "auth.invitation.accepted", request, mapOf("role" to JsonPrimitive(role)))
            if (enrolled != null) audit.record(this, account, "auth.totp.enrolled", request)
        }
    }

    private fun expectedStatus(kind: LinkKind) = if (kind == LinkKind.INVITATION) AccountStatus.INVITED else AccountStatus.ACTIVE

    private companion object {
        /** The name an authenticator app shows next to the code. */
        const val TOTP_ISSUER = "Restaurant Admin"
    }
}
