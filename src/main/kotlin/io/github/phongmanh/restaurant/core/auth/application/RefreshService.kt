package io.github.phongmanh.restaurant.core.auth.application

import io.github.phongmanh.restaurant.core.application.Actor
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.auth.crypto.AccessTokens
import io.github.phongmanh.restaurant.core.auth.crypto.Audience
import io.github.phongmanh.restaurant.core.auth.crypto.SecureTokens
import io.github.phongmanh.restaurant.core.auth.persistence.RefreshTokenRepository
import io.github.phongmanh.restaurant.core.auth.persistence.RevokeReason
import io.github.phongmanh.restaurant.core.auth.persistence.StaffAccountRepository
import io.github.phongmanh.restaurant.core.auth.persistence.StaffSessionRepository
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.StaffSessionId
import io.github.phongmanh.restaurant.core.observability.ServiceMeters
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * `POST /v1/auth/refresh` and `POST /v1/auth/sign-out` (core-and-auth §8.3, §8.4).
 *
 * **In plain words:** each refresh token works once and is swapped for a new one. If a used one
 * comes back after the [grace] period, someone has a copy, so the whole session ends, and the new
 * token stops working too. Within the grace period it's most likely the same browser, reloaded
 * before the new cookie arrived, so it gets a fresh sibling (CR-017). A lock on the account doesn't
 * stop a refresh (owner, 2026-10-15): someone who knows a manager's email can't sign the
 * restaurant's staff out during service.
 */
class RefreshService(
    private val transactor: Transactor,
    private val accounts: StaffAccountRepository,
    private val sessions: StaffSessionRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val issuer: SessionIssuer,
    private val accessTokens: AccessTokens,
    private val audit: AuthAudit,
    private val clock: Clock,
    private val meters: ServiceMeters,
    private val grace: Duration,
) {
    private sealed interface Outcome {
        class Refreshed(
            val issued: IssuedTokens,
            val byGrace: Boolean,
        ) : Outcome

        class Reused(
            val sessionId: StaffSessionId,
        ) : Outcome

        data object Invalid : Outcome
    }

    /**
     * New tokens for a valid [refreshToken] of [audience], or for one used within the grace period;
     * any failure is `401`. Not audited (§8.3), except reuse.
     */
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
                // The parent's row first, then this token's, then its siblings': one order for every refresh.
                refreshTokens.closeParentGrace(this, hash)
                val used = refreshTokens.use(this, hash, now)
                if (used != null) {
                    used.parentHash?.let { refreshTokens.supersedeSiblings(this, it, hash, now) }
                    issueFor(this, used.sessionId, audience, hash, now)?.let { Outcome.Refreshed(it, byGrace = false) } ?: Outcome.Invalid
                } else {
                    val state = refreshTokens.state(this, hash)
                    if (state == null || !state.used) {
                        Outcome.Invalid
                    } else {
                        graceSwap(this, hash, audience, now) ?: Outcome.Reused(state.sessionId)
                    }
                }
            }
        if (outcome is Outcome.Refreshed && outcome.byGrace) meters.refreshGrace(audience.value)
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
                transactor.globalTransaction { refreshTokens.state(this, SecureTokens.hash(token))?.sessionId }
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

    /** A used token's grace swap (§8.3, CR-017), or `null` when it doesn't qualify and counts as reuse. */
    private fun graceSwap(
        tx: TxScope,
        hash: ByteArray,
        audience: Audience,
        now: Instant,
    ): Outcome? {
        val sessionId =
            grace.takeIf { it.isPositive() }?.let { refreshTokens.graceSwap(tx, hash, now.minus(it.toJavaDuration()), now) }
                ?: return null
        return issueFor(tx, sessionId, audience, hash, now)?.let { Outcome.Refreshed(it, byGrace = true) } ?: Outcome.Invalid
    }

    /** New tokens for [sessionId] when it's live and of [audience], replacing the token [parentHash]. */
    private fun issueFor(
        tx: TxScope,
        sessionId: StaffSessionId,
        audience: Audience,
        parentHash: ByteArray,
        now: Instant,
    ): IssuedTokens? =
        sessions
            .findLive(tx, sessionId, now)
            ?.takeIf { it.audience == audience }
            ?.let { issuer.issue(tx, it, now, parentHash) }

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
