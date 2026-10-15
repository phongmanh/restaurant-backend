package io.github.phongmanh.restaurant.core.auth.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.auth.crypto.Audience
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.domain.StaffSessionId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.instant
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.core.persistence.strings
import io.github.phongmanh.restaurant.core.persistence.uuid
import java.time.Instant

/** Why a session ended (`staff_sessions.revoke_reason`). */
enum class RevokeReason(
    val value: String,
) {
    SIGN_OUT("sign_out"),
    REFRESH_REUSE("refresh_reuse"),
    PASSWORD_CHANGED("password_changed"),
    PASSWORD_RESET("password_reset"),
    ROLE_CHANGED("role_changed"),
    DEACTIVATED("deactivated"),
    TOTP_RESET("totp_reset"),
}

/** A live session: one sign-in. */
data class StaffSession(
    val id: StaffSessionId,
    val staffId: StaffId,
    val audience: Audience,
    val amr: List<String>,
    val expiresAt: Instant,
)

/** What a refresh token's hash finds, used or not. */
data class RefreshTokenState(
    val sessionId: StaffSessionId,
    val used: Boolean,
)

/**
 * `staff_sessions`, `staff_refresh_tokens` and `staff_mfa_challenges` (core-and-auth §9). Every
 * check of a time takes `now` from the service's clock, so tests can move it.
 */
class StaffSessionRepository {
    fun create(
        tx: TxScope,
        session: StaffSession,
        startedAt: Instant,
    ) {
        connection(tx)
            .prepareStatement("INSERT INTO staff_sessions (id, staff_id, audience, amr, started_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)")
            .use {
                it.bind(session.id.value, session.staffId.value, session.audience.value, session.amr, startedAt, session.expiresAt)
                it.executeUpdate()
            }
    }

    /** The session if it's live: not revoked, not expired, and its account still active. */
    fun findLive(
        tx: TxScope,
        id: StaffSessionId,
        now: Instant,
    ): StaffSession? =
        connection(tx)
            .prepareStatement(
                """
                SELECT s.id, s.staff_id, s.audience, s.amr, s.expires_at FROM staff_sessions s
                JOIN staff_accounts a ON a.id = s.staff_id
                WHERE s.id = ? AND s.revoked_at IS NULL AND s.expires_at > ? AND a.status = 'active'
                """.trimIndent(),
            ).use {
                it.bind(id.value, now).firstOrNull { rs ->
                    StaffSession(
                        id = StaffSessionId(rs.uuid("id")!!),
                        staffId = StaffId(rs.uuid("staff_id")!!),
                        audience = Audience.of(rs.getString("audience"))!!,
                        amr = rs.strings("amr"),
                        expiresAt = rs.instant("expires_at")!!,
                    )
                }
            }

    /** Ends one session. Returns its account if it was live until now. */
    fun revoke(
        tx: TxScope,
        id: StaffSessionId,
        reason: RevokeReason,
        now: Instant,
    ): StaffId? =
        connection(tx)
            .prepareStatement(
                "UPDATE staff_sessions SET revoked_at = ?, revoke_reason = ? WHERE id = ? AND revoked_at IS NULL RETURNING staff_id",
            ).use { it.bind(now, reason.value, id.value).firstOrNull { rs -> StaffId(rs.uuid("staff_id")!!) } }

    /** Ends every session of [staffId] (a password change or reset, §8.5, §8.7). Returns the IDs it ended. */
    fun revokeAll(
        tx: TxScope,
        staffId: StaffId,
        reason: RevokeReason,
        now: Instant,
    ): List<StaffSessionId> =
        connection(tx)
            .prepareStatement(
                "UPDATE staff_sessions SET revoked_at = ?, revoke_reason = ? WHERE staff_id = ? AND revoked_at IS NULL RETURNING id",
            ).use { it.bind(now, reason.value, staffId.value).list { rs -> StaffSessionId(rs.uuid("id")!!) } }

    fun issueRefreshToken(
        tx: TxScope,
        hash: ByteArray,
        sessionId: StaffSessionId,
        issuedAt: Instant,
        idleExpiresAt: Instant,
    ) {
        connection(tx)
            .prepareStatement("INSERT INTO staff_refresh_tokens (token_hash, session_id, issued_at, idle_expires_at) VALUES (?, ?, ?, ?)")
            .use { it.bind(hash, sessionId.value, issuedAt, idleExpiresAt).executeUpdate() }
    }

    /**
     * Uses a refresh token in one atomic step (§8.3): only an unused, unexpired one, and only once,
     * even when two requests bring it at the same moment. Returns its session.
     */
    fun useRefreshToken(
        tx: TxScope,
        hash: ByteArray,
        now: Instant,
    ): StaffSessionId? =
        connection(tx)
            .prepareStatement(
                "UPDATE staff_refresh_tokens SET used_at = ? " +
                    "WHERE token_hash = ? AND used_at IS NULL AND idle_expires_at > ? RETURNING session_id",
            ).use { it.bind(now, hash, now).firstOrNull { rs -> StaffSessionId(rs.uuid("session_id")!!) } }

    /** What a refresh token's hash finds, whether or not it can still be used. */
    fun refreshTokenState(
        tx: TxScope,
        hash: ByteArray,
    ): RefreshTokenState? =
        connection(tx)
            .prepareStatement("SELECT session_id, used_at FROM staff_refresh_tokens WHERE token_hash = ?")
            .use {
                it.bind(hash).firstOrNull { rs ->
                    RefreshTokenState(StaffSessionId(rs.uuid("session_id")!!), rs.instant("used_at") != null)
                }
            }

    fun createMfaChallenge(
        tx: TxScope,
        hash: ByteArray,
        staffId: StaffId,
        expiresAt: Instant,
    ) {
        connection(tx)
            .prepareStatement("INSERT INTO staff_mfa_challenges (token_hash, staff_id, expires_at) VALUES (?, ?, ?)")
            .use { it.bind(hash, staffId.value, expiresAt).executeUpdate() }
    }

    /** Uses up an MFA challenge (§8.2): only an unused, unexpired one, and only once. Returns its account. */
    fun consumeMfaChallenge(
        tx: TxScope,
        hash: ByteArray,
        now: Instant,
    ): StaffId? =
        connection(tx)
            .prepareStatement(
                "UPDATE staff_mfa_challenges SET used_at = ? " +
                    "WHERE token_hash = ? AND used_at IS NULL AND expires_at > ? RETURNING staff_id",
            ).use { it.bind(now, hash, now).firstOrNull { rs -> StaffId(rs.uuid("staff_id")!!) } }

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)
}
