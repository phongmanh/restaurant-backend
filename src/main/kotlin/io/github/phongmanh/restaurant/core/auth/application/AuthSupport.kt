package io.github.phongmanh.restaurant.core.auth.application

import io.github.phongmanh.restaurant.core.application.Actor
import io.github.phongmanh.restaurant.core.application.ActorType
import io.github.phongmanh.restaurant.core.application.AuditEvent
import io.github.phongmanh.restaurant.core.application.AuditLog
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.auth.crypto.AccessClaims
import io.github.phongmanh.restaurant.core.auth.crypto.AccessTokens
import io.github.phongmanh.restaurant.core.auth.crypto.Audience
import io.github.phongmanh.restaurant.core.auth.crypto.SecureTokens
import io.github.phongmanh.restaurant.core.auth.domain.SessionPolicy
import io.github.phongmanh.restaurant.core.auth.domain.StaffAccount
import io.github.phongmanh.restaurant.core.auth.persistence.StaffAccountRepository
import io.github.phongmanh.restaurant.core.auth.persistence.StaffSession
import io.github.phongmanh.restaurant.core.auth.persistence.StaffSessionRepository
import io.github.phongmanh.restaurant.core.domain.IdGenerator
import io.github.phongmanh.restaurant.core.domain.Rfc3339
import io.github.phongmanh.restaurant.core.domain.StaffSessionId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** What a finished sign-in or refresh hands the client (§8.1, §8.3). */
class IssuedTokens(
    val accessToken: String,
    val audience: Audience,
    /** Goes into the refresh cookie only; never into a body or a log. */
    val refreshToken: String,
    val refreshExpiresAt: Instant,
) {
    override fun toString(): String = "IssuedTokens(audience=$audience)"
}

/** Starts sessions and swaps refresh tokens (§8.3). */
class SessionIssuer(
    private val sessions: StaffSessionRepository,
    private val accessTokens: AccessTokens,
    private val tokens: SecureTokens,
    private val ids: IdGenerator,
) {
    /** A new session (one sign-in) for [account], with its first refresh token. */
    fun start(
        tx: TxScope,
        account: StaffAccount,
        audience: Audience,
        amr: List<String>,
        now: Instant,
    ): IssuedTokens {
        val session = StaffSession(StaffSessionId(ids.next()), account.id, audience, amr, now.plus(SessionPolicy.SESSION))
        sessions.create(tx, session, now)
        return issue(tx, session, now)
    }

    /** The next refresh token of a live [session], and a new access token. */
    fun issue(
        tx: TxScope,
        session: StaffSession,
        now: Instant,
    ): IssuedTokens {
        val refresh = tokens.generate()
        val idleExpiresAt = minOf(now.plus(SessionPolicy.REFRESH_IDLE), session.expiresAt)
        sessions.issueRefreshToken(tx, SecureTokens.hash(refresh), session.id, now, idleExpiresAt)
        val access = accessTokens.sign(AccessClaims(session.staffId.value, session.id.value, session.audience, session.amr))
        return IssuedTokens(access, session.audience, refresh, idleExpiresAt)
    }
}

/** The audit entries of §12 for an account, in its restaurant's chain (or the platform's for an admin). */
class AuthAudit(
    private val audit: AuditLog,
) {
    fun record(
        tx: TxScope,
        account: StaffAccount,
        action: String,
        request: RequestMeta,
        details: Map<String, JsonElement> = emptyMap(),
        actor: Actor = actorOf(account),
    ) {
        audit.record(
            tx,
            AuditEvent(
                restaurantId = account.restaurantId,
                actor = actor,
                action = action,
                targetType = TARGET_TYPE,
                targetId = account.id.value,
                details = JsonObject(details),
                request = request,
            ),
        )
    }

    /** An entry in the platform chain about no account, such as a sign-in with an unknown email. */
    fun recordPlatform(
        tx: TxScope,
        action: String,
        request: RequestMeta,
        details: Map<String, JsonElement>,
    ) {
        audit.record(tx, AuditEvent(null, Actor.ANONYMOUS, action, details = JsonObject(details), request = request))
    }

    companion object {
        const val TARGET_TYPE = "staff_account"

        fun actorOf(account: StaffAccount) = Actor(if (account.isPlatformAdmin) ActorType.ADMIN else ActorType.STAFF, account.id.value)

        fun amr(amr: List<String>): Map<String, JsonElement> = mapOf("amr" to JsonArray(amr.map(::JsonPrimitive)))
    }
}

/**
 * A failed password or TOTP code against an existing account (§8.1, §8.2): counts it towards the
 * lock, records [action], and records `auth.account.locked` if this failure locked the account.
 * Runs in the transaction of the account's audit chain, which then commits before the 401.
 */
class CredentialFailures(
    private val accounts: StaffAccountRepository,
    private val audit: AuthAudit,
) {
    fun record(
        tx: TxScope,
        account: StaffAccount,
        now: Instant,
        action: String,
        request: RequestMeta,
        details: Map<String, JsonElement> = emptyMap(),
    ) {
        val lockedUntil = accounts.recordFailure(tx, account.id, now)
        audit.record(tx, account, action, request, details, Actor.ANONYMOUS)
        if (lockedUntil != null) {
            val until = mapOf("until" to JsonPrimitive(Rfc3339.format(lockedUntil)))
            audit.record(tx, account, "auth.account.locked", request, until, Actor.SYSTEM)
        }
    }
}
