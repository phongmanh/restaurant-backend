package io.github.phongmanh.restaurant.core.auth.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.StaffSessionId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.instant
import io.github.phongmanh.restaurant.core.persistence.uuid
import java.time.Instant

/** What a refresh token's hash finds, used or not. */
data class RefreshTokenState(
    val sessionId: StaffSessionId,
    val used: Boolean,
)

/** A refresh token just used: its session, and the token it replaced (`null` for a sign-in's first). */
class UsedRefreshToken(
    val sessionId: StaffSessionId,
    val parentHash: ByteArray?,
)

/**
 * `staff_refresh_tokens` (core-and-auth §8.3, §9): issue, use, and the grace swap of CR-017. Every
 * check of a time takes `now` from the service's clock, so tests can move it. A refresh locks rows
 * in one order: the parent's, then the token's, then its siblings'.
 */
class RefreshTokenRepository {
    fun issue(
        tx: TxScope,
        hash: ByteArray,
        sessionId: StaffSessionId,
        issuedAt: Instant,
        idleExpiresAt: Instant,
        parentHash: ByteArray? = null,
    ) {
        connection(tx)
            .prepareStatement(
                "INSERT INTO staff_refresh_tokens (token_hash, session_id, issued_at, idle_expires_at, parent_hash) " +
                    "VALUES (?, ?, ?, ?, ?::bytea)",
            ).use { it.bind(hash, sessionId.value, issuedAt, idleExpiresAt, parentHash).executeUpdate() }
    }

    /**
     * Closes the grace period of the token [hash] replaced, before [hash] is used (§8.3, CR-017): once
     * a child is used, the parent coming back is a replay. Taking the parent's row first makes a grace
     * swap of the parent and a use of its child take turns, always in the order parent → child.
     */
    fun closeParentGrace(
        tx: TxScope,
        hash: ByteArray,
    ) {
        connection(tx)
            .prepareStatement(
                "UPDATE staff_refresh_tokens SET grace_swaps = ? " +
                    "WHERE token_hash = (SELECT parent_hash FROM staff_refresh_tokens WHERE token_hash = ?)",
            ).use { it.bind(MAX_GRACE_SWAPS, hash).executeUpdate() }
    }

    /**
     * Uses a refresh token in one atomic step (§8.3): only an unused, unexpired one, and only once,
     * even when two requests bring it at the same moment. Returns its session and parent.
     */
    fun use(
        tx: TxScope,
        hash: ByteArray,
        now: Instant,
    ): UsedRefreshToken? =
        connection(tx)
            .prepareStatement(
                "UPDATE staff_refresh_tokens SET used_at = ? " +
                    "WHERE token_hash = ? AND used_at IS NULL AND idle_expires_at > ? RETURNING session_id, parent_hash",
            ).use {
                it.bind(now, hash, now).firstOrNull { rs ->
                    UsedRefreshToken(StaffSessionId(rs.uuid("session_id")!!), rs.getBytes("parent_hash"))
                }
            }

    /**
     * Marks the unused siblings of the token [hash], children of [parentHash], used and superseded
     * (CR-017): the browser keeps whichever one it used first, and another that comes back is reuse.
     */
    fun supersedeSiblings(
        tx: TxScope,
        parentHash: ByteArray,
        hash: ByteArray,
        now: Instant,
    ) {
        connection(tx)
            .prepareStatement(
                "UPDATE staff_refresh_tokens SET used_at = ?, superseded_at = ? " +
                    "WHERE parent_hash = ? AND token_hash <> ? AND used_at IS NULL",
            ).use { it.bind(now, now, parentHash, hash).executeUpdate() }
    }

    /**
     * One grace swap of a used token (§8.3, CR-017): only one used after [usedAfter], not superseded,
     * unexpired, and with swaps left. The row lock serialises two swaps of the same token. Returns its
     * session.
     */
    fun graceSwap(
        tx: TxScope,
        hash: ByteArray,
        usedAfter: Instant,
        now: Instant,
    ): StaffSessionId? =
        connection(tx)
            .prepareStatement(
                "UPDATE staff_refresh_tokens SET grace_swaps = grace_swaps + 1 " +
                    "WHERE token_hash = ? AND used_at > ? AND superseded_at IS NULL AND grace_swaps < ? " +
                    "AND idle_expires_at > ? RETURNING session_id",
            ).use { it.bind(hash, usedAfter, MAX_GRACE_SWAPS, now).firstOrNull { rs -> StaffSessionId(rs.uuid("session_id")!!) } }

    /** What a refresh token's hash finds, whether or not it can still be used. */
    fun state(
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

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)

    companion object {
        /** Grace swaps one used refresh token may take (§8.3, CR-017); a used child sets its parent to this. */
        const val MAX_GRACE_SWAPS = 3
    }
}
