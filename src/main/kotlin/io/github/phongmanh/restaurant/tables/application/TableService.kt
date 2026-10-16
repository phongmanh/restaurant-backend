package io.github.phongmanh.restaurant.tables.application

import io.github.phongmanh.restaurant.core.application.AuditEvent
import io.github.phongmanh.restaurant.core.application.AuditLog
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.IdGenerator
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.TableId
import io.github.phongmanh.restaurant.core.persistence.advisoryLock
import io.github.phongmanh.restaurant.restaurants.application.RESTAURANT_TARGET
import io.github.phongmanh.restaurant.tables.crypto.QrCodes
import io.github.phongmanh.restaurant.tables.domain.DiningTable
import io.github.phongmanh.restaurant.tables.domain.NewTable
import io.github.phongmanh.restaurant.tables.domain.TableChanges
import io.github.phongmanh.restaurant.tables.domain.TableRules
import io.github.phongmanh.restaurant.tables.persistence.RowLock
import io.github.phongmanh.restaurant.tables.persistence.SessionRepository
import io.github.phongmanh.restaurant.tables.persistence.TableRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock

/**
 * A manager's tables (tables §6, FR-030), in the restaurant's own transaction.
 *
 * Writes that check the count or a number take the restaurant's tables lock first (an advisory
 * lock), so two creates can't both pass the 300th slot or the same number (CLAUDE.md). Checks come
 * in the order of core-and-auth §3: missing → `404`, stale `If-Match` → `412`, then `409`.
 */
class TableService(
    private val transactor: Transactor,
    private val tables: TableRepository,
    private val sessions: SessionRepository,
    private val audit: AuditLog,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun create(
        manager: TableManager,
        table: NewTable,
        request: RequestMeta,
    ): DiningTable =
        transactor.inTransaction(manager.restaurantId) {
            val rid = manager.restaurantId
            advisoryLock(this, tablesLock(rid))
            if (tables.count(this, rid) >= TableRules.MAX_TABLES) throw DomainError.TableLimitReached()
            if (tables.numberTaken(this, rid, table.number, null)) throw DomainError.DuplicateName()
            val id = TableId(ids.next())
            tables.insert(this, rid, id, table, clock.instant())
            audit.record(this, AuditEvent(rid, manager.actor, "table.created", TableTargets.TABLE, id.value, request = request))
            tables.find(this, rid, id)!!
        }

    suspend fun get(
        floor: FloorStaff,
        id: TableId,
    ): DiningTable =
        transactor.inTransaction(floor.restaurantId) { tables.find(this, floor.restaurantId, id) } ?: throw DomainError.NotFound()

    /** Disabling a table with guests → `409 table_has_open_session` (FR-030). */
    suspend fun update(
        manager: TableManager,
        id: TableId,
        version: Int,
        changes: TableChanges,
        request: RequestMeta,
    ): DiningTable =
        transactor.inTransaction(manager.restaurantId) {
            val rid = manager.restaurantId
            advisoryLock(this, tablesLock(rid))
            val current = tables.lock(this, rid, id, RowLock.UPDATE) ?: throw DomainError.NotFound()
            if (current.version != version) throw DomainError.PreconditionFailed()
            val number = changes.number
            if (number != null && tables.numberTaken(this, rid, number, id)) throw DomainError.DuplicateName()
            if (changes.enabled == false && sessions.openAt(this, rid, id) != null) throw DomainError.TableHasOpenSession()
            tables.update(this, rid, id, changes, clock.instant())
            val fields = mapOf("fields" to JsonArray(changes.fields.map(::JsonPrimitive)))
            audit.record(this, AuditEvent(rid, manager.actor, "table.updated", TableTargets.TABLE, id.value, JsonObject(fields), request))
            tables.find(this, rid, id)!!
        }

    /** Soft delete; with guests → `409 table_has_open_session`. Its printed codes stop working (§2). */
    suspend fun delete(
        manager: TableManager,
        id: TableId,
        version: Int,
        request: RequestMeta,
    ) = transactor.inTransaction(manager.restaurantId) {
        val rid = manager.restaurantId
        val current = tables.lock(this, rid, id, RowLock.UPDATE) ?: throw DomainError.NotFound()
        if (current.version != version) throw DomainError.PreconditionFailed()
        if (sessions.openAt(this, rid, id) != null) throw DomainError.TableHasOpenSession()
        tables.delete(this, rid, id, clock.instant())
        audit.record(this, AuditEvent(rid, manager.actor, "table.deleted", TableTargets.TABLE, id.value, request = request))
    }
}

/** A table's printable code (tables §6). */
data class QrCode(
    val tableId: TableId,
    val number: String,
    val url: String,
    val keyVersion: Int,
)

/**
 * QR codes (tables §2, §6, FR-031): read them for printing, and rotate them. Rotating bumps the
 * table's `qr_key_version`, so every code printed before is refused at once, while guests already
 * seated keep their session. Nothing secret is stored: a code is made again from the row.
 */
class QrService(
    private val transactor: Transactor,
    private val tables: TableRepository,
    private val codes: QrCodes,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    suspend fun code(
        manager: TableManager,
        id: TableId,
    ): QrCode =
        transactor
            .inTransaction(
                manager.restaurantId,
            ) { tables.find(this, manager.restaurantId, id) }
            ?.let { code(manager.restaurantId, it) }
            ?: throw DomainError.NotFound()

    /** Every table's code, in floor order, for printing a sheet. */
    suspend fun codes(manager: TableManager): List<QrCode> =
        transactor
            .inTransaction(manager.restaurantId) { tables.list(this, manager.restaurantId) }
            .sortedWith(TableRules.FLOOR_ORDER)
            .map { code(manager.restaurantId, it) }

    suspend fun rotate(
        manager: TableManager,
        id: TableId,
        request: RequestMeta,
    ): QrCode =
        transactor.inTransaction(manager.restaurantId) {
            val rid = manager.restaurantId
            tables.lock(this, rid, id, RowLock.UPDATE) ?: throw DomainError.NotFound()
            tables.rotate(this, rid, id, clock.instant())
            val table = tables.find(this, rid, id)!!
            val details = mapOf("key_version" to JsonPrimitive(table.qrKeyVersion))
            audit.record(
                this,
                AuditEvent(rid, manager.actor, "table.qr_rotated", TableTargets.TABLE, id.value, JsonObject(details), request),
            )
            code(rid, table)
        }

    /** The "QR leak" runbook (§2, architecture §13): every table at once. */
    suspend fun rotateAll(
        manager: TableManager,
        request: RequestMeta,
    ): List<QrCode> =
        transactor
            .inTransaction(manager.restaurantId) {
                val rid = manager.restaurantId
                // The tables lock first, so two runs don't lock the rows in different orders.
                advisoryLock(this, tablesLock(rid))
                val rotated = tables.rotate(this, rid, null, clock.instant())
                val details = mapOf("tables" to JsonPrimitive(rotated))
                audit.record(
                    this,
                    AuditEvent(rid, manager.actor, "table.qr_rotated_all", RESTAURANT_TARGET, rid.value, JsonObject(details), request),
                )
                tables.list(this, rid)
            }.sortedWith(TableRules.FLOOR_ORDER)
            .map { code(manager.restaurantId, it) }

    private fun code(
        restaurantId: RestaurantId,
        table: DiningTable,
    ) = QrCode(table.id, table.number, codes.url(restaurantId, table.id, table.qrKeyVersion), table.qrKeyVersion)
}

/** The restaurant's tables lock: creates, renames and rotate-all take turns on it. */
internal fun tablesLock(restaurantId: RestaurantId) = "dining_tables:$restaurantId"
