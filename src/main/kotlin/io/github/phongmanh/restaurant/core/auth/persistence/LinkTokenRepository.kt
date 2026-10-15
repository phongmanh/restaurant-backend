package io.github.phongmanh.restaurant.core.auth.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.uuid
import java.time.Instant

enum class LinkKind(
    val value: String,
) {
    INVITATION("invitation"),
    PASSWORD_RESET("password_reset"),
    ;

    companion object {
        fun of(value: String): LinkKind = entries.first { it.value == value }
    }
}

data class LinkToken(
    val staffId: StaffId,
    val kind: LinkKind,
)

/** A link to store: the SHA-256 of its token, never the token (core-and-auth §8.5). */
class NewLink(
    val hash: ByteArray,
    val staffId: StaffId,
    val kind: LinkKind,
    val createdBy: StaffId,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/**
 * `staff_link_tokens` (core-and-auth §8.5, §9). The restaurants feature issues the links through
 * [issue]; sign-in reads and uses them up. A link is valid until it's used, replaced or expires.
 */
class LinkTokenRepository {
    /** Stores a new link's hash and replaces the person's earlier open link of the same kind (§8.5). */
    fun issue(
        tx: TxScope,
        link: NewLink,
    ) {
        val conn = connection(tx)
        conn
            .prepareStatement("UPDATE staff_link_tokens SET superseded_at = ? WHERE staff_id = ? AND kind = ? AND $OPEN")
            .use { it.bind(link.createdAt, link.staffId.value, link.kind.value).executeUpdate() }
        conn
            .prepareStatement(
                "INSERT INTO staff_link_tokens (token_hash, staff_id, kind, created_by, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
            ).use {
                it
                    .bind(
                        link.hash,
                        link.staffId.value,
                        link.kind.value,
                        link.createdBy.value,
                        link.createdAt,
                        link.expiresAt,
                    ).executeUpdate()
            }
    }

    /** Replaces every open link of the person, of either kind: a deactivated or erased account keeps none. */
    fun supersedeAll(
        tx: TxScope,
        staffId: StaffId,
        now: Instant,
    ) {
        connection(tx)
            .prepareStatement("UPDATE staff_link_tokens SET superseded_at = ? WHERE staff_id = ? AND $OPEN")
            .use { it.bind(now, staffId.value).executeUpdate() }
    }

    /** The link if it's still valid, for inspecting it and before using it. */
    fun findValid(
        tx: TxScope,
        hash: ByteArray,
        now: Instant,
    ): LinkToken? =
        connection(tx)
            .prepareStatement(
                "SELECT staff_id, kind FROM staff_link_tokens WHERE token_hash = ? AND $VALID",
            ).use { it.bind(hash, now).firstOrNull { rs -> LinkToken(StaffId(rs.uuid("staff_id")!!), LinkKind.of(rs.getString("kind"))) } }

    /** Uses the link up in one atomic step; `false` if another request used it first or it's no longer valid. */
    fun use(
        tx: TxScope,
        hash: ByteArray,
        now: Instant,
    ): Boolean =
        connection(tx)
            .prepareStatement(
                "UPDATE staff_link_tokens SET used_at = ? WHERE token_hash = ? AND $VALID",
            ).use { it.bind(now, hash, now).executeUpdate() == 1 }

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)

    private companion object {
        /** Not used, not replaced, not expired (the one parameter is `now`). */
        const val VALID = "used_at IS NULL AND superseded_at IS NULL AND expires_at > ?"

        /** Not used and not replaced yet, expired or not. */
        const val OPEN = "used_at IS NULL AND superseded_at IS NULL"
    }
}
