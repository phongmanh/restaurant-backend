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

/**
 * `staff_link_tokens` (core-and-auth §8.5, §9). The restaurants feature issues the links; here
 * they're read and used up. A link is valid until it's used, replaced or expires.
 */
class LinkTokenRepository {
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
    }
}
