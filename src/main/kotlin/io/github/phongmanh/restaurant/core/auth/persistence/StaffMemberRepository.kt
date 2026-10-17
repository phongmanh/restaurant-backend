package io.github.phongmanh.restaurant.core.auth.persistence

import io.github.phongmanh.restaurant.core.application.StaffRole
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.auth.domain.AccountStatus
import io.github.phongmanh.restaurant.core.auth.domain.Anonymised
import io.github.phongmanh.restaurant.core.auth.domain.MemberChanges
import io.github.phongmanh.restaurant.core.auth.domain.NewAccount
import io.github.phongmanh.restaurant.core.auth.domain.StaffMember
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.instant
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.core.persistence.uuid
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** Which members a list shows (restaurants §7). */
data class MemberFilter(
    val status: AccountStatus? = null,
    val role: StaffRole? = null,
)

/** What the staff rules count in a restaurant (restaurants §7). */
data class SeatCounts(
    /** Invited and active accounts: the 100-account limit counts these (ASSUMPTION-12). */
    val seats: Int,
    val activeManagers: Int,
)

/**
 * `staff_accounts` as staff management sees it (restaurants §7, §10). Every method that a
 * restaurant's manager can reach takes the restaurant's ID and filters by it (IT-CORE-07).
 * Anonymised accounts are left out everywhere: to staff management they're gone. The lifecycle
 * changes ([insert], [deactivate], [reactivate]) serve platform admins' accounts too; they take the
 * member found earlier and match its restaurant, which for an admin is none.
 */
class StaffMemberRepository {
    /** Inserts the account, or returns `null` if an account with that email exists (one `ON CONFLICT`, no error to log). */
    fun insert(
        tx: TxScope,
        id: StaffId,
        account: NewAccount,
        now: Instant,
    ): StaffMember? =
        connection(tx)
            .prepareStatement(
                """
                INSERT INTO staff_accounts (id, email, display_name, phone, restaurant_id, role, is_platform_admin, status,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'invited', ?, ?)
                ON CONFLICT (email) DO NOTHING
                RETURNING $MEMBER_COLUMNS
                """.trimIndent(),
            ).use {
                it
                    .bind(
                        id.value,
                        account.email,
                        account.displayName,
                        account.phone,
                        account.restaurantId?.value,
                        account.role?.let(::roleValue),
                        account.isPlatformAdmin,
                        now,
                        now,
                    ).firstOrNull(::member)
            }

    fun find(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: StaffId,
    ): StaffMember? = one(connection(tx), "$SELECT_MEMBER WHERE id = ? AND restaurant_id = ? AND $LIVE", id.value, restaurantId.value)

    /** A restaurant's staff member, whichever restaurant: for the platform's privacy requests (§10). */
    fun findRestaurantStaff(
        tx: TxScope,
        id: StaffId,
    ): StaffMember? = one(connection(tx), "$SELECT_MEMBER WHERE id = ? AND NOT is_platform_admin AND $LIVE", id.value)

    /** Up to [limit] members after [after] (`display_name`, `id`), sorted by name, then ID. */
    fun list(
        tx: TxScope,
        restaurantId: RestaurantId,
        filter: MemberFilter,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<StaffMember> {
        val conditions = mutableListOf("restaurant_id = ?", LIVE)
        val params = mutableListOf<Any?>(restaurantId.value)
        filter.status?.let {
            conditions += "status = ?"
            params += it.value
        }
        filter.role?.let {
            conditions += "role = ?"
            params += roleValue(it)
        }
        return page(connection(tx), conditions, params, after, limit)
    }

    fun counts(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): SeatCounts =
        connection(tx)
            .prepareStatement(
                """
                SELECT count(*) FILTER (WHERE status IN ('invited', 'active')),
                       count(*) FILTER (WHERE status = 'active' AND role = 'manager')
                FROM staff_accounts WHERE restaurant_id = ?
                """.trimIndent(),
            ).use { it.bind(restaurantId.value).firstOrNull { rs -> SeatCounts(rs.getInt(1), rs.getInt(2)) }!! }

    /**
     * Applies [changes] to a restaurant's [member] if its version is still [version]. Returns the
     * number of rows changed: none means it's gone or someone saved in between.
     */
    fun update(
        tx: TxScope,
        member: StaffMember,
        version: Int,
        changes: MemberChanges,
        now: Instant,
    ): Int =
        connection(tx)
            .prepareStatement(
                """
                UPDATE staff_accounts SET display_name = coalesce(?, display_name), phone = coalesce(?, phone),
                  role = coalesce(?, role), updated_at = ?, version = version + 1
                WHERE id = ? AND restaurant_id = ? AND version = ? AND $LIVE
                """.trimIndent(),
            ).use {
                it
                    .bind(
                        changes.displayName,
                        changes.phone,
                        changes.role?.let(::roleValue),
                        now,
                        member.id.value,
                        requireNotNull(member.restaurantId).value,
                        version,
                    ).executeUpdate()
            }

    fun deactivate(
        tx: TxScope,
        member: StaffMember,
        now: Instant,
    ) = execute(
        tx,
        "UPDATE staff_accounts SET status = 'deactivated', deactivated_at = ?, updated_at = ?, version = version + 1 WHERE $SAME_ACCOUNT",
        now,
        now,
        member.id.value,
        member.restaurantId?.value,
    )

    /** Back to `active`, or to `invited` if the account never set a password (§7: the old password still works). */
    fun reactivate(
        tx: TxScope,
        member: StaffMember,
        now: Instant,
    ) = execute(
        tx,
        "UPDATE staff_accounts SET status = CASE WHEN password_hash IS NULL THEN 'invited' ELSE 'active' END, " +
            "deactivated_at = NULL, updated_at = ?, version = version + 1 WHERE $SAME_ACCOUNT",
        now,
        member.id.value,
        member.restaurantId?.value,
    )

    /**
     * Deactivates and anonymises the account at once, as the daily retention job does (core-and-auth
     * §9): only the ID stays, so the audit log still points to it.
     */
    fun anonymise(
        tx: TxScope,
        member: StaffMember,
        now: Instant,
    ) = execute(
        tx,
        """
        UPDATE staff_accounts SET email = ?, display_name = ?, phone = NULL, password_hash = NULL, password_changed_at = NULL,
          totp_secret = NULL, totp_pending_secret = NULL, totp_last_step = NULL, failed_count = 0, failed_window_start = NULL,
          locked_until = NULL, status = 'deactivated', deactivated_at = coalesce(deactivated_at, ?), anonymised_at = ?,
          updated_at = ?, version = version + 1
        WHERE $SAME_ACCOUNT
        """.trimIndent(),
        Anonymised.email(member.id),
        Anonymised.DISPLAY_NAME,
        now,
        now,
        now,
        member.id.value,
        member.restaurantId?.value,
    )

    private fun execute(
        tx: TxScope,
        sql: String,
        vararg params: Any?,
    ) {
        connection(tx).prepareStatement(sql).use { it.bind(*params).executeUpdate() }
    }
}

/** `staff_accounts` rows of platform admins (restaurants §8): they belong to no restaurant. */
class PlatformAdminRepository {
    fun find(
        tx: TxScope,
        id: StaffId,
    ): StaffMember? = one(connection(tx), "$SELECT_MEMBER WHERE id = ? AND $ADMIN AND $LIVE", id.value)

    /** For the operator's commands only (restaurants §5, §8): the admin with [email], lower-cased. */
    fun findByEmail(
        tx: TxScope,
        email: String,
    ): StaffMember? = one(connection(tx), "$SELECT_MEMBER WHERE email = ? AND $ADMIN AND $LIVE", email)

    fun list(
        tx: TxScope,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<StaffMember> = page(connection(tx), mutableListOf(ADMIN, LIVE), mutableListOf(), after, limit)

    fun countActive(tx: TxScope): Int =
        connection(tx).prepareStatement("SELECT count(*) FROM staff_accounts WHERE $ADMIN AND status = 'active'").use {
            it.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    /** A lost authenticator (§8): the admin starts again from an invitation, without password or TOTP secret. */
    fun resetToInvited(
        tx: TxScope,
        id: StaffId,
        now: Instant,
    ) {
        connection(tx)
            .prepareStatement(
                """
                UPDATE staff_accounts SET status = 'invited', password_hash = NULL, password_changed_at = NULL, totp_secret = NULL,
                  totp_pending_secret = NULL, totp_last_step = NULL, failed_count = 0, failed_window_start = NULL, locked_until = NULL,
                  updated_at = ?, version = version + 1
                WHERE id = ? AND $ADMIN
                """.trimIndent(),
            ).use { it.bind(now, id.value).executeUpdate() }
    }
}

private const val MEMBER_COLUMNS =
    "id, restaurant_id, email, display_name, phone, role, is_platform_admin, status, created_at, deactivated_at, version"
private const val SELECT_MEMBER = "SELECT $MEMBER_COLUMNS FROM staff_accounts"

/** Not anonymised. */
private const val LIVE = "anonymised_at IS NULL"

/**
 * The member's own row and restaurant (IT-CORE-07): a platform admin's has none. Callers have found
 * the member in its restaurant already; this keeps the update itself from reaching another's.
 */
private const val SAME_ACCOUNT = "id = ? AND restaurant_id IS NOT DISTINCT FROM ?::uuid"
private const val ADMIN = "is_platform_admin AND restaurant_id IS NULL"

private fun connection(tx: TxScope): Connection = ExposedTransactor.connection(tx)

private fun roleValue(role: StaffRole) = role.name.lowercase(Locale.ROOT)

private fun one(
    connection: Connection,
    sql: String,
    vararg params: Any?,
): StaffMember? = connection.prepareStatement(sql).use { it.bind(*params).firstOrNull(::member) }

/** A page sorted by name, then ID, after [after]. */
private fun page(
    connection: Connection,
    conditions: MutableList<String>,
    params: MutableList<Any?>,
    after: Pair<String, UUID>?,
    limit: Int,
): List<StaffMember> {
    after?.let { (name, id) ->
        conditions += "(display_name, id) > (?, ?)"
        params += name
        params += id
    }
    params += limit
    return connection
        .prepareStatement("$SELECT_MEMBER WHERE ${conditions.joinToString(" AND ")} ORDER BY display_name, id LIMIT ?")
        .use { it.bind(params).list(::member) }
}

private fun member(rs: ResultSet) =
    StaffMember(
        id = StaffId(rs.uuid("id")!!),
        restaurantId = rs.uuid("restaurant_id")?.let(::RestaurantId),
        email = rs.getString("email"),
        displayName = rs.getString("display_name"),
        phone = rs.getString("phone"),
        role = rs.getString("role")?.let { StaffRole.valueOf(it.uppercase(Locale.ROOT)) },
        isPlatformAdmin = rs.getBoolean("is_platform_admin"),
        status = AccountStatus.of(rs.getString("status")),
        createdAt = rs.instant("created_at")!!,
        deactivatedAt = rs.instant("deactivated_at"),
        version = rs.getInt("version"),
    )
