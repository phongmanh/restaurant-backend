package io.github.phongmanh.restaurant.core.auth.persistence

import io.github.phongmanh.restaurant.core.application.StaffRole
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.auth.domain.AccountStatus
import io.github.phongmanh.restaurant.core.auth.domain.LockoutPolicy
import io.github.phongmanh.restaurant.core.auth.domain.StaffAccount
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.instant
import io.github.phongmanh.restaurant.core.persistence.uuid
import java.sql.ResultSet
import java.time.Instant
import java.util.Locale

/**
 * `staff_accounts`, a global table without RLS (core-and-auth §9). The methods a restaurant's staff
 * can trigger take that restaurant's ID and filter by it ([findInRestaurant]); the rest are reached
 * only through a secret the caller holds (an email and password, or a token) or by the platform.
 */
class StaffAccountRepository {
    fun findByEmail(
        tx: TxScope,
        email: String,
    ): StaffAccount? = query(tx, "$SELECT WHERE email = ?", email)

    fun findById(
        tx: TxScope,
        id: StaffId,
    ): StaffAccount? = query(tx, "$SELECT WHERE id = ?", id.value)

    /** The account only if it belongs to [restaurantId]: what a restaurant's staff may reach (IT-CORE-07). */
    fun findInRestaurant(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: StaffId,
    ): StaffAccount? = query(tx, "$SELECT WHERE id = ? AND restaurant_id = ?", id.value, restaurantId.value)

    /** A platform admin's account; never a restaurant's staff. */
    fun findPlatformAdmin(
        tx: TxScope,
        id: StaffId,
    ): StaffAccount? = query(tx, "$SELECT WHERE id = ? AND is_platform_admin AND restaurant_id IS NULL", id.value)

    /**
     * Counts a failed password or TOTP code in one atomic step (§8.1): a new 15-min window if the
     * last one has passed, else one more; the 10th failure in a window locks the account for 15 min.
     * A locked account isn't counted, so its lock can't be extended without end. Returns the lock's
     * end if this failure locked the account, else `null`.
     */
    fun recordFailure(
        tx: TxScope,
        id: StaffId,
        now: Instant,
    ): Instant? {
        val windowStart = now.minus(LockoutPolicy.WINDOW)
        val lockUntil = now.plus(LockoutPolicy.LOCK)
        val newWindow = "(failed_window_start IS NULL OR failed_window_start <= ?)"
        return connection(tx)
            .prepareStatement(
                """
                UPDATE staff_accounts SET
                  failed_count = CASE WHEN $newWindow THEN 1 ELSE failed_count + 1 END,
                  failed_window_start = CASE WHEN $newWindow THEN ? ELSE failed_window_start END,
                  locked_until = CASE WHEN NOT $newWindow AND failed_count + 1 >= ? THEN ? ELSE NULL END,
                  updated_at = ?
                WHERE id = ? AND (locked_until IS NULL OR locked_until <= ?)
                RETURNING locked_until
                """.trimIndent(),
            ).use {
                it.bind(windowStart, windowStart, now, windowStart, LockoutPolicy.MAX_FAILURES, lockUntil, now, id.value, now)
                it.firstOrNull { rs -> rs.instant("locked_until") }
            }
    }

    /**
     * A correct password (or, for an admin, a correct TOTP code). It applies only while the account
     * is still active and unlocked, so a sign-in that raced a lock or a deactivation fails; it
     * stores [rehash] if the hash used older parameters, and with [resetFailures] clears the
     * counter. An admin's password step doesn't clear it: only the finished sign-in does, so
     * knowing the password doesn't buy fresh tries at the TOTP code. Returns whether it applied.
     */
    fun recordSuccess(
        tx: TxScope,
        id: StaffId,
        now: Instant,
        rehash: String?,
        resetFailures: Boolean,
    ): Boolean =
        connection(tx)
            .prepareStatement(
                """
                UPDATE staff_accounts SET
                  failed_count = CASE WHEN ? THEN 0 ELSE failed_count END,
                  failed_window_start = CASE WHEN ? THEN NULL ELSE failed_window_start END,
                  password_hash = coalesce(?, password_hash), updated_at = ?
                WHERE id = ? AND status = 'active' AND (locked_until IS NULL OR locked_until <= ?)
                """.trimIndent(),
            ).use { it.bind(resetFailures, resetFailures, rehash, now, id.value, now).executeUpdate() == 1 }

    /** Stores a new password and clears the lock counters (§8.5, §8.7). */
    fun setPassword(
        tx: TxScope,
        id: StaffId,
        hash: String,
        now: Instant,
    ) {
        connection(tx)
            .prepareStatement(
                """
                UPDATE staff_accounts SET password_hash = ?, password_changed_at = ?, failed_count = 0,
                  failed_window_start = NULL, locked_until = NULL, updated_at = ?, version = version + 1
                WHERE id = ?
                """.trimIndent(),
            ).use { it.bind(hash, now, now, id.value).executeUpdate() }
    }

    /**
     * Accepts an invitation (§8.5): the password, and for an admin the enrolled TOTP secret and the
     * step its first code used, and `active`. Only an invited account is activated.
     */
    fun activate(
        tx: TxScope,
        id: StaffId,
        passwordHash: String,
        totp: Pair<ByteArray, Long>?,
        now: Instant,
    ): Boolean =
        connection(tx)
            .prepareStatement(
                """
                UPDATE staff_accounts SET status = 'active', password_hash = ?, password_changed_at = ?,
                  totp_secret = ?, totp_last_step = ?, totp_pending_secret = NULL,
                  failed_count = 0, failed_window_start = NULL, locked_until = NULL, updated_at = ?, version = version + 1
                WHERE id = ? AND status = 'invited'
                """.trimIndent(),
            ).use { it.bind(passwordHash, now, totp?.first, totp?.second, now, id.value).executeUpdate() == 1 }

    private fun query(
        tx: TxScope,
        sql: String,
        vararg params: Any?,
    ): StaffAccount? = connection(tx).prepareStatement(sql).use { it.bind(*params).firstOrNull(::account) }

    private companion object {
        const val SELECT =
            "SELECT id, email, display_name, restaurant_id, role, is_platform_admin, status, password_hash, " +
                "totp_secret, totp_last_step, locked_until FROM staff_accounts"
    }
}

private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)

private fun account(rs: ResultSet) =
    StaffAccount(
        id = StaffId(rs.uuid("id")!!),
        email = rs.getString("email"),
        displayName = rs.getString("display_name"),
        restaurantId = rs.uuid("restaurant_id")?.let(::RestaurantId),
        role = rs.getString("role")?.let { StaffRole.valueOf(it.uppercase(Locale.ROOT)) },
        isPlatformAdmin = rs.getBoolean("is_platform_admin"),
        status = AccountStatus.of(rs.getString("status")),
        passwordHash = rs.getString("password_hash"),
        totpSecret = rs.getBytes("totp_secret"),
        totpLastStep = rs.getLong("totp_last_step").takeUnless { rs.wasNull() },
        lockedUntil = rs.instant("locked_until"),
    )
