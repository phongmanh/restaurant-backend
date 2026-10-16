package io.github.phongmanh.restaurant.tables.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.TableId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.core.persistence.uuid
import io.github.phongmanh.restaurant.tables.domain.DiningTable
import io.github.phongmanh.restaurant.tables.domain.NewTable
import io.github.phongmanh.restaurant.tables.domain.TableChanges
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant

/** How a table row is locked: to change it, or only to keep it from changing until the commit. */
enum class RowLock(
    val sql: String,
) {
    UPDATE("FOR UPDATE"),
    SHARE("FOR SHARE"),
}

/**
 * `dining_tables` (tables §5, §6). Every method takes the restaurant and filters by it; deleted
 * tables are never read back.
 *
 * Lock order (CLAUDE.md): a write that locks a table row does so before it touches a session, so
 * the table row comes first, then the table session.
 */
class TableRepository {
    fun find(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: TableId,
    ): DiningTable? = one(tx, "$SELECT AND id = ?", restaurantId, id)

    /** [find] with the row locked until the transaction ends. */
    fun lock(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: TableId,
        lock: RowLock,
    ): DiningTable? = one(tx, "$SELECT AND id = ? ${lock.sql}", restaurantId, id)

    /**
     * [lock], deleted tables included ([DiningTable.deleted]): staff opening or moving a session to
     * a deleted table get `409 table_unavailable`, as for a disabled one (§3.4, §8).
     */
    fun lockAny(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: TableId,
        lock: RowLock,
    ): DiningTable? = one(tx, "$SELECT_ANY AND id = ? ${lock.sql}", restaurantId, id)

    /** Every table, unsorted; the floor order is [io.github.phongmanh.restaurant.tables.domain.TableRules.FLOOR_ORDER]. */
    fun list(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): List<DiningTable> = connection(tx).prepareStatement(SELECT).use { it.bind(restaurantId.value).list(::table) }

    fun count(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): Int =
        connection(tx)
            .prepareStatement("SELECT count(*) FROM dining_tables WHERE restaurant_id = ? AND deleted_at IS NULL")
            .use { it.bind(restaurantId.value).firstOrNull { rs -> rs.getInt(1) } } ?: 0

    /** Whether another table already has [number], ignoring case. */
    fun numberTaken(
        tx: TxScope,
        restaurantId: RestaurantId,
        number: String,
        except: TableId?,
    ): Boolean =
        connection(tx)
            .prepareStatement(
                "SELECT 1 FROM dining_tables WHERE restaurant_id = ? AND deleted_at IS NULL AND lower(number) = lower(?) " +
                    "AND id IS DISTINCT FROM ?::uuid",
            ).use { it.bind(restaurantId.value, number, except?.value).firstOrNull { true } } ?: false

    fun insert(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: TableId,
        table: NewTable,
        now: Instant,
    ) {
        uniqueNumber {
            connection(tx)
                .prepareStatement(
                    "INSERT INTO dining_tables (id, restaurant_id, number, area, seats, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?::text, ?::smallint, ?, ?)",
                ).use { it.bind(id.value, restaurantId.value, table.number, table.area, table.seats, now, now).executeUpdate() }
        }
    }

    /** Applies [changes] and increases the version; the caller checked the version under the row lock. */
    fun update(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: TableId,
        changes: TableChanges,
        now: Instant,
    ) {
        uniqueNumber {
            connection(tx)
                .prepareStatement(
                    """
                    UPDATE dining_tables SET number = coalesce(?::text, number),
                      area = CASE WHEN ?::text IS NULL THEN area ELSE nullif(?::text, '') END,
                      seats = coalesce(?::smallint, seats), enabled = coalesce(?::boolean, enabled),
                      updated_at = ?, version = version + 1
                    WHERE restaurant_id = ? AND id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                ).use {
                    it
                        .bind(changes.number, changes.area, changes.area, changes.seats, changes.enabled, now, restaurantId.value, id.value)
                        .executeUpdate()
                }
        }
    }

    /** Soft delete: the number is free again, and printed codes fail the QR check (§2). */
    fun delete(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: TableId,
        now: Instant,
    ) {
        connection(tx)
            .prepareStatement(
                "UPDATE dining_tables SET deleted_at = ?, updated_at = ?, version = version + 1 " +
                    "WHERE restaurant_id = ? AND id = ? AND deleted_at IS NULL",
            ).use { it.bind(now, now, restaurantId.value, id.value).executeUpdate() }
    }

    /**
     * `qr_key_version += 1` on one table (or, with [id] `null`, on every table): every code printed
     * before stops working (§2). The row version stays, as no field of the table changed.
     */
    fun rotate(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: TableId?,
        now: Instant,
    ): Int =
        connection(tx)
            .prepareStatement(
                "UPDATE dining_tables SET qr_key_version = qr_key_version + 1, updated_at = ? " +
                    "WHERE restaurant_id = ? AND deleted_at IS NULL AND (?::uuid IS NULL OR id = ?::uuid)",
            ).use { it.bind(now, restaurantId.value, id?.value, id?.value).executeUpdate() }

    private fun one(
        tx: TxScope,
        sql: String,
        restaurantId: RestaurantId,
        id: TableId,
    ): DiningTable? = connection(tx).prepareStatement(sql).use { it.bind(restaurantId.value, id.value).firstOrNull(::table) }

    /** The unique index decides a race the name check didn't see: `409 duplicate_name`. */
    private inline fun uniqueNumber(write: () -> Unit) {
        try {
            write()
        } catch (e: SQLException) {
            if (e.sqlState == UNIQUE_VIOLATION) throw DomainError.DuplicateName()
            throw e
        }
    }

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)

    internal companion object {
        const val UNIQUE_VIOLATION = "23505"

        const val SELECT_ANY =
            "SELECT id, number, area, seats, enabled, qr_key_version, version, deleted_at IS NOT NULL AS deleted FROM dining_tables " +
                "WHERE restaurant_id = ?"
        const val SELECT = "$SELECT_ANY AND deleted_at IS NULL"

        fun table(rs: ResultSet) =
            DiningTable(
                id = TableId(rs.uuid("id")!!),
                number = rs.getString("number"),
                area = rs.getString("area"),
                seats = rs.getInt("seats").takeUnless { rs.wasNull() },
                enabled = rs.getBoolean("enabled"),
                qrKeyVersion = rs.getInt("qr_key_version"),
                version = rs.getInt("version"),
                deleted = rs.getBoolean("deleted"),
            )
    }
}
