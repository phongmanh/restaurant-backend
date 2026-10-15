package io.github.phongmanh.restaurant.core.auth.application

import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.auth.crypto.Audience
import io.github.phongmanh.restaurant.core.auth.crypto.SecretBox
import io.github.phongmanh.restaurant.core.auth.crypto.SecureTokens
import io.github.phongmanh.restaurant.core.auth.crypto.Totp
import io.github.phongmanh.restaurant.core.auth.domain.AccountStatus
import io.github.phongmanh.restaurant.core.auth.domain.StaffAccount
import io.github.phongmanh.restaurant.core.auth.persistence.StaffAccountRepository
import io.github.phongmanh.restaurant.core.auth.persistence.StaffSessionRepository
import io.github.phongmanh.restaurant.core.auth.persistence.StaffTotpRepository
import io.github.phongmanh.restaurant.core.domain.DomainError
import java.time.Clock
import java.time.Instant

/**
 * `POST /v1/auth/mfa` (core-and-auth §8.2): an admin's TOTP code after the password. The challenge
 * is used up whatever happens; a wrong code counts towards the same lock as a wrong password, and
 * the admin signs in again. A code's step can't be used twice.
 */
class MfaService(
    private val transactor: Transactor,
    private val accounts: StaffAccountRepository,
    private val totp: StaffTotpRepository,
    private val sessions: StaffSessionRepository,
    private val totpKeys: SecretBox,
    private val issuer: SessionIssuer,
    private val failures: CredentialFailures,
    private val audit: AuthAudit,
    private val clock: Clock,
) {
    suspend fun complete(
        mfaToken: String,
        code: String,
        request: RequestMeta,
    ): IssuedTokens {
        val now = clock.instant()
        val issued =
            transactor.platformTransaction("auth: MFA step") {
                val staffId = sessions.consumeMfaChallenge(this, SecureTokens.hash(mfaToken), now)
                val account = staffId?.let { accounts.findPlatformAdmin(this, it) }
                account?.let { check(this, it, code, now, request) }
            }
        return issued ?: throw DomainError.InvalidCredentials()
    }

    private fun check(
        tx: TxScope,
        account: StaffAccount,
        code: String,
        now: Instant,
        request: RequestMeta,
    ): IssuedTokens? {
        val usable = account.status == AccountStatus.ACTIVE && !account.isLocked(now) && account.totpSecret != null
        val step =
            if (usable) {
                Totp.verify(totpKeys.open(account.totpSecret, aad(account)), code, now.epochSecond, account.totpLastStep)
            } else {
                null
            }
        val accepted =
            step != null &&
                totp.useTotpStep(tx, account.id, step, now) &&
                accounts.recordSuccess(tx, account.id, now, rehash = null, resetFailures = true)
        if (!accepted) {
            failures.record(tx, account, now, "auth.mfa.failed", request)
            return null
        }
        val amr = listOf(SignInService.PWD, SignInService.OTP)
        val issued = issuer.start(tx, account, Audience.ADMIN, amr, now)
        audit.record(tx, account, SignInService.SUCCEEDED, request, AuthAudit.amr(amr))
        return issued
    }

    companion object {
        /** The TOTP secret is bound to its account, so one copied onto another row doesn't decrypt. */
        fun aad(account: StaffAccount): ByteArray = account.id.toString().toByteArray(Charsets.US_ASCII)
    }
}
