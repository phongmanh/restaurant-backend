package io.github.phongmanh.restaurant.core.auth.persistence

import io.github.phongmanh.restaurant.core.application.StaffRole
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.domain.StaffSessionId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.instant
import io.github.phongmanh.restaurant.core.persistence.strings
import io.github.phongmanh.restaurant.core.persistence.uuid
import java.sql.ResultSet
import java.time.Instant
import java.util.Locale

/** What the database says about a token's account and session (core-and-auth §6, step 2). */
data class CallerRow(
    val restaurantId: RestaurantId?,
    val role: StaffRole?,
    val isPlatformAdmin: Boolean,
    val restaurantSuspended: Boolean,
)

/** `GET /v1/auth/me` (§8.8). */
data class MeRow(
    val staffId: StaffId,
    val email: String,
    val displayName: String,
    val role: StaffRole?,
    val restaurant: Pair<RestaurantId, String>?,
    val isPlatformAdmin: Boolean,
    val amr: List<String>,
    val sessionExpiresAt: Instant,
)

/** The reads behind every authenticated call. All filter by the account and its live session. */
class CallerRepository {
    /** The one query per staff or admin call (§6): no row means the token no longer stands for anyone. */
    fun lookup(
        tx: TxScope,
        staffId: StaffId,
        sessionId: StaffSessionId,
        now: Instant,
    ): CallerRow? =
        connection(tx)
            .prepareStatement(
                """
                SELECT a.restaurant_id, a.role, a.is_platform_admin, r.status AS restaurant_status
                FROM staff_accounts a
                JOIN staff_sessions s ON s.staff_id = a.id
                LEFT JOIN restaurants r ON r.id = a.restaurant_id
                WHERE a.id = ? AND s.id = ? AND a.status = 'active' AND s.revoked_at IS NULL AND s.expires_at > ?
                """.trimIndent(),
            ).use {
                it.bind(staffId.value, sessionId.value, now).firstOrNull { rs ->
                    CallerRow(
                        restaurantId = rs.uuid("restaurant_id")?.let(::RestaurantId),
                        role = role(rs),
                        isPlatformAdmin = rs.getBoolean("is_platform_admin"),
                        restaurantSuspended = rs.getString("restaurant_status") == "suspended",
                    )
                }
            }

    /**
     * The caller's own account and session. [restaurantId] is the caller's restaurant (`null` for an
     * admin) and is part of the filter, as every query a restaurant's staff can trigger (IT-CORE-07).
     */
    fun me(
        tx: TxScope,
        staffId: StaffId,
        sessionId: StaffSessionId,
        restaurantId: RestaurantId?,
    ): MeRow? =
        connection(tx)
            .prepareStatement(
                """
                SELECT a.id, a.email, a.display_name, a.role, a.is_platform_admin, r.id AS r_id, r.name AS r_name, s.amr, s.expires_at
                FROM staff_accounts a
                JOIN staff_sessions s ON s.staff_id = a.id
                LEFT JOIN restaurants r ON r.id = a.restaurant_id
                WHERE a.id = ? AND s.id = ? AND a.restaurant_id IS NOT DISTINCT FROM ?
                """.trimIndent(),
            ).use {
                it.bind(staffId.value, sessionId.value, restaurantId?.value).firstOrNull { rs ->
                    MeRow(
                        staffId = StaffId(rs.uuid("id")!!),
                        email = rs.getString("email"),
                        displayName = rs.getString("display_name"),
                        role = role(rs),
                        restaurant = rs.uuid("r_id")?.let { id -> RestaurantId(id) to rs.getString("r_name") },
                        isPlatformAdmin = rs.getBoolean("is_platform_admin"),
                        amr = rs.strings("amr"),
                        sessionExpiresAt = rs.instant("expires_at")!!,
                    )
                }
            }

    /** A restaurant's name, for greeting a person who opens their invitation (§8.5). */
    fun restaurantName(
        tx: TxScope,
        id: RestaurantId,
    ): String? =
        connection(tx).prepareStatement("SELECT name FROM restaurants WHERE id = ?").use {
            it.bind(id.value).firstOrNull { rs ->
                rs.getString(1)
            }
        }

    private fun role(rs: ResultSet) = rs.getString("role")?.let { StaffRole.valueOf(it.uppercase(Locale.ROOT)) }

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)
}
