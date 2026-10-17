package io.github.phongmanh.restaurant.reports.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.core.persistence.localDate
import io.github.phongmanh.restaurant.core.persistence.uuid
import java.time.Instant
import java.time.LocalDate

/** How far the nightly rebuild has got for one restaurant (reports §3, `sales_reconciliation`). */
data class Reconciliation(
    val reconciledThrough: LocalDate,
    val lastMismatchOn: LocalDate?,
)

/** `sales_reconciliation`. [all] is for the job's platform transaction; the rest take the restaurant. */
class ReconciliationRepository {
    /** Every restaurant's row. Platform transactions only. */
    fun all(tx: TxScope): Map<RestaurantId, Reconciliation> =
        ExposedTransactor
            .connection(tx)
            .prepareStatement("SELECT restaurant_id, reconciled_through, last_mismatch_on FROM sales_reconciliation")
            .use { statement ->
                statement.list { RestaurantId(it.uuid("restaurant_id")!!) to reconciliation(it) }.toMap()
            }

    /** The restaurant's row, locked until the transaction ends, so two runs can't both move it. */
    fun lock(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): Reconciliation? =
        ExposedTransactor
            .connection(tx)
            .prepareStatement(
                "SELECT reconciled_through, last_mismatch_on FROM sales_reconciliation WHERE restaurant_id = ? FOR NO KEY UPDATE",
            ).use { it.bind(restaurantId.value).firstOrNull(::reconciliation) }

    /** Moves the restaurant on to [through]; [mismatchOn], if any, replaces an older `last_mismatch_on`. */
    fun save(
        tx: TxScope,
        restaurantId: RestaurantId,
        through: LocalDate,
        at: Instant,
        mismatchOn: LocalDate?,
    ) {
        ExposedTransactor
            .connection(tx)
            .prepareStatement(
                "INSERT INTO sales_reconciliation AS t (restaurant_id, reconciled_through, last_run_at, last_mismatch_on) " +
                    "VALUES (?, ?, ?, ?::date) ON CONFLICT (restaurant_id) DO UPDATE SET " +
                    "reconciled_through = excluded.reconciled_through, last_run_at = excluded.last_run_at, " +
                    "last_mismatch_on = greatest(t.last_mismatch_on, excluded.last_mismatch_on)",
            ).use { it.bind(restaurantId.value, through, at, mismatchOn).executeUpdate() }
    }

    private fun reconciliation(rs: java.sql.ResultSet) =
        Reconciliation(rs.localDate("reconciled_through")!!, rs.localDate("last_mismatch_on"))
}
