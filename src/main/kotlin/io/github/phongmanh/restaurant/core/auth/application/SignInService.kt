package io.github.phongmanh.restaurant.core.auth.application

import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.application.inTransaction
import io.github.phongmanh.restaurant.core.auth.crypto.Audience
import io.github.phongmanh.restaurant.core.auth.crypto.EmailHmac
import io.github.phongmanh.restaurant.core.auth.crypto.PasswordHashing
import io.github.phongmanh.restaurant.core.auth.crypto.SecureTokens
import io.github.phongmanh.restaurant.core.auth.domain.AccountStatus
import io.github.phongmanh.restaurant.core.auth.domain.PasswordPolicy
import io.github.phongmanh.restaurant.core.auth.domain.SessionPolicy
import io.github.phongmanh.restaurant.core.auth.domain.StaffAccount
import io.github.phongmanh.restaurant.core.auth.domain.normaliseEmail
import io.github.phongmanh.restaurant.core.auth.persistence.StaffAccountRepository
import io.github.phongmanh.restaurant.core.auth.persistence.StaffSessionRepository
import io.github.phongmanh.restaurant.core.domain.DomainError
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.Instant

sealed interface SignInResult {
    /** A staff member is signed in. */
    class Tokens(
        val issued: IssuedTokens,
    ) : SignInResult

    /** A platform admin's password was right; the TOTP step follows (§8.2). */
    class MfaRequired(
        val mfaToken: String,
        val expiresInSeconds: Long,
    ) : SignInResult {
        override fun toString(): String = "MfaRequired(***)"
    }
}

/**
 * `POST /v1/auth/sign-in` (core-and-auth §8.1). Every failure is the same `401
 * invalid_credentials`, and every path runs Argon2id once (against a dummy hash when there's no
 * usable account), so neither the answer nor its time reveals which emails exist.
 *
 * The hash runs outside any transaction, so no connection is held during it. The account is read
 * first (a global table), and the outcome is written in the account's own audit chain.
 */
class SignInService(
    private val transactor: Transactor,
    private val accounts: StaffAccountRepository,
    private val sessions: StaffSessionRepository,
    private val hashing: PasswordHashing,
    private val issuer: SessionIssuer,
    private val failures: CredentialFailures,
    private val audit: AuthAudit,
    private val emailHmac: EmailHmac,
    private val tokens: SecureTokens,
    private val clock: Clock,
) {
    suspend fun signIn(
        email: String,
        password: String,
        request: RequestMeta,
    ): SignInResult {
        val normalisedEmail = normaliseEmail(email)
        val account = transactor.globalTransaction { accounts.findByEmail(this, normalisedEmail) }
        val usable = account?.takeIf { it.status == AccountStatus.ACTIVE && !it.isLocked(clock.instant()) && it.passwordHash != null }
        val typed = PasswordPolicy.normalise(password)
        val matched = hashing.verifyOrDummy(typed, usable?.passwordHash)
        val rehash = if (matched && hashing.needsRehash(usable!!.passwordHash!!)) hashing.hash(typed) else null
        val now = clock.instant()

        if (account == null) {
            transactor.platformTransaction("auth: sign-in with an unknown email") {
                audit.recordPlatform(
                    this,
                    FAILED,
                    request,
                    mapOf("reason" to JsonPrimitive("unknown_email"), "email_hmac" to JsonPrimitive(emailHmac.of(normalisedEmail))),
                )
            }
            throw DomainError.InvalidCredentials()
        }
        val result =
            transactor.inTransaction(account.tenant) {
                if (matched && accounts.recordSuccess(this, account.id, now, rehash, resetFailures = !account.isPlatformAdmin)) {
                    succeed(this, account, now, request)
                } else {
                    val reason =
                        when {
                            account.isLocked(now) || matched -> "locked"
                            account.status != AccountStatus.ACTIVE || account.passwordHash == null -> "inactive"
                            else -> "bad_password"
                        }
                    failures.record(this, account, now, FAILED, request, mapOf("reason" to JsonPrimitive(reason)))
                    null
                }
            }
        return result ?: throw DomainError.InvalidCredentials()
    }

    private fun succeed(
        tx: TxScope,
        account: StaffAccount,
        now: Instant,
        request: RequestMeta,
    ): SignInResult {
        if (account.isPlatformAdmin) {
            val mfaToken = tokens.generate()
            sessions.createMfaChallenge(tx, SecureTokens.hash(mfaToken), account.id, now.plus(SessionPolicy.MFA_CHALLENGE))
            return SignInResult.MfaRequired(mfaToken, SessionPolicy.MFA_CHALLENGE.seconds)
        }
        val amr = listOf(PWD)
        val issued = issuer.start(tx, account, Audience.STAFF, amr, now)
        audit.record(tx, account, SUCCEEDED, request, AuthAudit.amr(amr))
        return SignInResult.Tokens(issued)
    }

    companion object {
        const val FAILED = "auth.sign_in.failed"
        const val SUCCEEDED = "auth.sign_in.succeeded"
        const val PWD = "pwd"
        const val OTP = "otp"
    }
}
