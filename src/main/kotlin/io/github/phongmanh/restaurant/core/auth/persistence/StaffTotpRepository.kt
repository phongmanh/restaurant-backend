package io.github.phongmanh.restaurant.core.auth.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import java.time.Instant

/** The TOTP columns of `staff_accounts`: an admin's last used step and the secret during enrolment (§8.2, §8.5). */
class StaffTotpRepository {
    /** Saves the TOTP step just used, only if it's later than the last one, so a code can't be used twice (§8.2). */
    fun useTotpStep(
        tx: TxScope,
        id: StaffId,
        step: Long,
        now: Instant,
    ): Boolean =
        connection(tx)
            .prepareStatement(
                "UPDATE staff_accounts SET totp_last_step = ?, updated_at = ? " +
                    "WHERE id = ? AND (totp_last_step IS NULL OR totp_last_step < ?)",
            ).use { it.bind(step, now, id.value, step).executeUpdate() == 1 }

    /** Keeps a TOTP secret during enrolment, replacing any earlier one (§8.5). */
    fun setPendingTotp(
        tx: TxScope,
        id: StaffId,
        sealed: ByteArray,
        now: Instant,
    ) {
        connection(tx)
            .prepareStatement("UPDATE staff_accounts SET totp_pending_secret = ?, updated_at = ? WHERE id = ?")
            .use { it.bind(sealed, now, id.value).executeUpdate() }
    }

    /** The pending TOTP secret of an enrolment, if any. */
    fun pendingTotp(
        tx: TxScope,
        id: StaffId,
    ): ByteArray? =
        connection(tx)
            .prepareStatement("SELECT totp_pending_secret FROM staff_accounts WHERE id = ?")
            .use { it.bind(id.value).firstOrNull { rs -> rs.getBytes(1) } }

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)
}
