package io.github.phongmanh.restaurant.tables.application

import io.github.phongmanh.restaurant.core.application.AuditEvent
import io.github.phongmanh.restaurant.core.application.AuditLog
import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.RateLimits
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.auth.RateLimitSettings
import io.github.phongmanh.restaurant.core.domain.DeviceId
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.FieldError
import io.github.phongmanh.restaurant.core.domain.IdGenerator
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.domain.TableId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.realtime.application.EventAudience
import io.github.phongmanh.restaurant.realtime.application.EventLog
import io.github.phongmanh.restaurant.realtime.application.EventSubject
import io.github.phongmanh.restaurant.restaurants.application.RestaurantDirectory
import io.github.phongmanh.restaurant.restaurants.application.RestaurantProfile
import io.github.phongmanh.restaurant.restaurants.application.TenantStatus
import io.github.phongmanh.restaurant.restaurants.domain.RestaurantStatus
import io.github.phongmanh.restaurant.tables.crypto.GuestClaims
import io.github.phongmanh.restaurant.tables.crypto.GuestTokens
import io.github.phongmanh.restaurant.tables.crypto.QrClaims
import io.github.phongmanh.restaurant.tables.crypto.QrCodes
import io.github.phongmanh.restaurant.tables.domain.DiningTable
import io.github.phongmanh.restaurant.tables.domain.SessionStatus
import io.github.phongmanh.restaurant.tables.domain.TableSession
import io.github.phongmanh.restaurant.tables.domain.WaiterCall
import io.github.phongmanh.restaurant.tables.persistence.RowLock
import io.github.phongmanh.restaurant.tables.persistence.SessionLock
import io.github.phongmanh.restaurant.tables.persistence.SessionRepository
import io.github.phongmanh.restaurant.tables.persistence.TableRepository
import io.github.phongmanh.restaurant.tables.persistence.WaiterCallRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.Instant

/**
 * Opens a table's session, or finds the open one (tables §3.1, §3.4), inside the caller's
 * transaction, after the caller locked the table row `FOR SHARE` and checked it's available.
 */
class SessionOpener(
    private val sessions: SessionRepository,
    private val events: EventLog,
    private val ids: IdGenerator,
) {
    /** The table's open session, and whether this call created it. */
    fun openOrJoin(
        tx: TxScope,
        restaurantId: RestaurantId,
        table: DiningTable,
        now: Instant,
        openedBy: StaffId? = null,
    ): Pair<TableSession, Boolean> {
        repeat(ATTEMPTS) {
            val created = sessions.openIfNone(tx, restaurantId, TableSessionId(ids.next()), table.id, now, openedBy)
            // Not created: one is open there, unless it closed between the insert and this read; then try again.
            val session = sessions.openAt(tx, restaurantId, table.id) ?: return@repeat
            if (created) {
                val subject = EventSubject(TableEvents.SESSION_AGGREGATE, session.id.value, session.version)
                events.append(tx, restaurantId, TableEvents.SESSION_OPENED, subject, EventAudience.STAFF)
            }
            return session to created
        }
        throw DomainError.ServiceUnavailable()
    }

    private companion object {
        const val ATTEMPTS = 3
    }
}

/** The scan limits of tables §9: per device and per IP before the QR check, per table after it. */
class ScanLimits(
    private val limits: RateLimits,
    private val settings: RateLimitSettings,
) {
    suspend fun device(id: DeviceId) = limits.check(settings.policy(TableLimits.QR_DEVICE), id.toString())

    suspend fun ip(ip: String?) = limits.check(settings.policy(TableLimits.QR_IP), ip ?: "unknown")

    suspend fun table(id: TableId) = limits.check(settings.policy(TableLimits.QR_TABLE), id.toString())

    suspend fun waiterCall(session: TableSessionId) = limits.check(settings.policy(TableLimits.WAITER_CALL), session.toString())
}

/** A session token for one device, and the session it opens. */
data class SessionGrant(
    val token: String,
    val session: TableSession,
    val restaurantId: RestaurantId,
    val restaurant: RestaurantProfile,
)

/** What a guest sees of their session (§3.3): the session, its restaurant, and their calls. */
data class GuestView(
    val session: TableSession,
    val restaurantId: RestaurantId,
    val restaurant: RestaurantProfile,
    val calls: List<WaiterCall>,
)

/**
 * The guest side of table sessions (tables §3.1–3.3, FR-001): open or join from a QR code, and read.
 *
 * The same QR code from the same device twice gives the same session with a fresh token, which is
 * why the operation takes no `Idempotency-Key` and stores no answer (§13 Q1, CR-005): storing it
 * would keep a bearer token for 24 h.
 */
class GuestSessionService(
    private val transactor: Transactor,
    private val tables: TableRepository,
    private val sessions: SessionRepository,
    private val calls: WaiterCallRepository,
    private val opener: SessionOpener,
    private val codes: QrCodes,
    private val tokens: GuestTokens,
    private val tenants: TenantStatus,
    private val directory: RestaurantDirectory,
    private val limits: ScanLimits,
    private val clock: Clock,
) {
    /**
     * Every QR failure is the same `400 qr_token_invalid`: forged, of an unknown key, rotated, or of
     * a deleted table or an unknown restaurant (§2). The device ID only names the token's `sub` and
     * counts the device's scans; it isn't stored (§13 Q3).
     */
    suspend fun openOrJoin(
        qrToken: String,
        deviceId: DeviceId,
        ip: String?,
    ): SessionGrant {
        limits.device(deviceId)
        limits.ip(ip)
        val claims = codes.verify(qrToken) ?: throw DomainError.QrTokenInvalid()
        limits.table(claims.tableId)
        val rid = claims.restaurantId
        return transactor.inTransaction(rid) {
            val status = tenants.of(this, rid)
            val table = tables.lock(this, rid, claims.tableId, RowLock.SHARE)
            refusal(status, table, claims)?.let { throw it }
            val now = clock.instant()
            val (session, created) = opener.openOrJoin(this, rid, table!!, now)
            if (!created) sessions.touch(this, rid, session.id, now)
            val token = tokens.sign(GuestClaims(deviceId, session.id, rid))
            SessionGrant(token, session, rid, directory.profile(this, rid)!!)
        }
    }

    /**
     * Why a verified code can't seat anyone, in the order of §3.1: the database check of §2 (the
     * restaurant and the table exist and the code's version is current), then suspension, then a
     * disabled table. `null`: it can.
     */
    private fun refusal(
        status: RestaurantStatus?,
        table: DiningTable?,
        claims: QrClaims,
    ): DomainError? =
        when {
            status == null || table == null || table.qrKeyVersion != claims.keyVersion -> DomainError.QrTokenInvalid()
            status == RestaurantStatus.SUSPENDED -> DomainError.RestaurantSuspended()
            !table.enabled -> DomainError.TableUnavailable()
            else -> null
        }

    suspend fun view(guest: Caller.Guest): GuestView =
        transactor.inTransaction(guest.restaurantId) {
            val rid = guest.restaurantId
            val session = sessions.find(this, rid, guest.sessionId) ?: throw DomainError.TableSessionClosed()
            GuestView(session, rid, directory.profile(this, rid)!!, calls.latestPerKind(this, rid, session.id))
        }
}

/**
 * The staff side of table sessions (tables §3.4, FR-032): open without a scan, read, move.
 *
 * Lock order (CLAUDE.md): the table row, then the session row, then the event counter and the
 * audit head, in every transaction here.
 */
class StaffSessionService(
    private val transactor: Transactor,
    private val tables: TableRepository,
    private val sessions: SessionRepository,
    private val opener: SessionOpener,
    private val events: EventLog,
    private val audit: AuditLog,
    private val clock: Clock,
    private val summaries: SessionSummaries,
) {
    /** For guests without a phone (FR-014): the table's open session, created if there's none. */
    suspend fun open(
        floor: FloorStaff,
        tableId: TableId,
        request: RequestMeta,
    ): Pair<TableSession, Boolean> =
        transactor.inTransaction(floor.restaurantId) {
            val rid = floor.restaurantId
            val table = tables.lockAny(this, rid, tableId, RowLock.SHARE) ?: throw DomainError.NotFound()
            if (!table.available) throw DomainError.TableUnavailable()
            val (session, created) = opener.openOrJoin(this, rid, table, clock.instant(), floor.staffId)
            if (created) {
                audit.record(
                    this,
                    AuditEvent(
                        rid,
                        floor.actor,
                        "table_session.opened_by_staff",
                        TableTargets.SESSION,
                        session.id.value,
                        request = request,
                    ),
                )
            }
            session to created
        }

    /** Any staff role (§8); with the bill summary from orders (§3.4, orders §16). */
    suspend fun get(
        caller: Caller.Staff,
        id: TableSessionId,
    ): Pair<TableSession, BillSummary> =
        transactor.inTransaction(caller.restaurantId) {
            val session = sessions.find(this, caller.restaurantId, id) ?: throw DomainError.NotFound()
            session to (summaries.of(this, caller.restaurantId, listOf(id))[id] ?: BillSummary(0, 0))
        }

    /**
     * Moves an open session to a free table (FR-032); its orders and bill move with it, as they
     * belong to the session. Two moves of the same session at once: one `200`, one `412`.
     */
    suspend fun move(
        floor: FloorStaff,
        id: TableSessionId,
        toTableId: TableId,
        version: Int,
        request: RequestMeta,
    ): TableSession =
        transactor.inTransaction(floor.restaurantId) {
            val rid = floor.restaurantId
            val target = tables.lockAny(this, rid, toTableId, RowLock.SHARE) ?: throw DomainError.NotFound()
            val session = sessions.lock(this, rid, id, SessionLock.UPDATE) ?: throw DomainError.NotFound()
            if (target.id == session.tableId) {
                throw DomainError.ValidationFailed(listOf(FieldError("/to_table_id", "same_table", "the session is at that table")))
            }
            if (session.status != SessionStatus.OPEN) throw DomainError.SessionNotOpen()
            if (session.version != version) throw DomainError.PreconditionFailed()
            if (!target.available) throw DomainError.TableUnavailable()
            if (sessions.openAt(this, rid, target.id) != null) throw DomainError.TableOccupied()
            sessions.move(this, rid, id, target.id, clock.instant())
            val moved = sessions.find(this, rid, id)!!
            val subject = EventSubject(TableEvents.SESSION_AGGREGATE, id.value, moved.version)
            events.append(this, rid, TableEvents.SESSION_MOVED, subject, EventAudience.STAFF_AND_SESSION, id)
            val details = mapOf("from" to JsonPrimitive(session.tableId.toString()), "to" to JsonPrimitive(target.id.toString()))
            audit.record(
                this,
                AuditEvent(rid, floor.actor, "table_session.moved", TableTargets.SESSION, id.value, JsonObject(details), request),
            )
            moved
        }
}
