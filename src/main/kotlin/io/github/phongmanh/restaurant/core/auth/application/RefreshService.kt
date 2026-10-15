package io.github.phongmanh.restaurant.core.auth.application

import io.github.phongmanh.restaurant.core.application.Actor
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.auth.crypto.AccessTokens
import io.github.phongmanh.restaurant.core.auth.crypto.Audience
import io.github.phongmanh.restaurant.core.auth.crypto.SecureTokens
import io.github.phongmanh.restaurant.core.auth.persistence.RevokeReason
import io.github.phongmanh.restaurant.core.auth.persistence.StaffAccountRepository
import io.github.phongmanh.restaurant.core.auth.persistence.StaffSessionRepository
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.StaffSessionId
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock

/**
 * `POST /v1/auth/refresh` and `POST /v1/auth/sign-out` (core-and-auth §8.3, §8.4).
 *
 * **In plain words:** each refresh token works once and is swapped for a new one. If a used one
 * ever comes back, someone has a copy, so the whole session ends, and the new token stops working
 * too. A lock on the account doesn't stop a refresh (owner, 2026-10-15): someone who knows a
 * manager's email can't sign the restaurant's staff out during service.
 */
class RefreshService(
    private val transactor: Transactor,
    private val accounts: StaffAccountRepository,
    private val sessions: StaffSessionRepository,
    private val issuer: SessionIssuer,
    private val accessTokens: AccessTokens,
    private val audit: AuthAudit,
    private val clock: Clock,
) {
    private sealed interface Outcome {
        class Refreshed(
            val issued: IssuedTokens,
        ) : Outcome

        class Reused(
            val sessionId: StaffSessionId,
        ) : Outcome

        data object Invalid : Outcome
    }

    /** New tokens for a valid [refreshToken] of [audience]; any failure is `401`. Not audited (§8.3), except reuse. */
    suspend fun refresh(
        refreshToken: String,
        audience: Audience,
        request: RequestMeta,
    ): IssuedTokens {
        val hash = SecureTokens.hash(refreshToken)
        val now = clock.instant()
        // Global tables only, on the app pool: a refresh is the busiest auth call (≈ 6/s at 5,000 staff).
        val outcome =
            transactor.globalTransaction {
                val sessionId = sessions.useRefreshToken(this, hash, now)
                if (sessionId == null) {
                    val state = sessions.refreshTokenState(this, hash)
                    if (state?.used == true) Outcome.Reused(state.sessionId) else Outcome.Invalid
                } else {
                    val session = sessions.findLive(this, sessionId, now)?.takeIf { it.audience == audience }
                    if (session == null) Outcome.Invalid else Outcome.Refreshed(issuer.issue(this, session, now))
                }
            }
        if (outcome is Outcome.Reused) {
            transactor.platformTransaction("auth: refresh token reuse") {
                end(this, outcome.sessionId, RevokeReason.REFRESH_REUSE, "auth.refresh.reuse_detected", request)
            }
        }
        return (outcome as? Outcome.Refreshed)?.issued ?: throw DomainError.Unauthenticated()
    }

    /**
     * Ends the session found by the refresh cookie, the access token, or both (§8.4). Never fails:
     * the app can always sign out cleanly, even when nothing matched.
     */
    suspend fun signOut(
        refreshToken: String?,
        accessToken: String?,
        request: RequestMeta,
    ) {
        val fromCookie =
            refreshToken?.let { token ->
                transactor.globalTransaction { sessions.refreshTokenState(this, SecureTokens.hash(token))?.sessionId }
            }
        val fromToken =
            accessToken
                ?.let {
                    accessTokens.verify(
                        it,
                        setOf(Audience.STAFF, Audience.ADMIN),
                    )
                }?.let { StaffSessionId(it.sessionId) }
        val ids = listOfNotNull(fromCookie, fromToken).distinct()
        if (ids.isEmpty()) return
        transactor.platformTransaction("auth: sign-out") {
            ids.forEach { end(this, it, RevokeReason.SIGN_OUT, "auth.sign_out", request) }
        }
    }

    /** Revokes a session if it's still live, and records [action] in its account's audit chain. */
    private fun end(
        tx: TxScope,
        sessionId: StaffSessionId,
        reason: RevokeReason,
        action: String,
        request: RequestMeta,
    ) {
        val staffId = sessions.revoke(tx, sessionId, reason, clock.instant()) ?: return
        val account = accounts.findById(tx, staffId) ?: return
        val actor = if (reason == RevokeReason.SIGN_OUT) AuthAudit.actorOf(account) else Actor.ANONYMOUS
        audit.record(tx, account, action, request, mapOf("session_id" to JsonPrimitive(sessionId.toString())), actor)
    }
}
