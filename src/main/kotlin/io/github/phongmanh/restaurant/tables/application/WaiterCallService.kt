package io.github.phongmanh.restaurant.tables.application

import io.github.phongmanh.restaurant.core.application.AuditEvent
import io.github.phongmanh.restaurant.core.application.AuditLog
import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.IdGenerator
import io.github.phongmanh.restaurant.realtime.application.EventAudience
import io.github.phongmanh.restaurant.realtime.application.EventLog
import io.github.phongmanh.restaurant.realtime.application.EventSubject
import io.github.phongmanh.restaurant.tables.domain.TableRules
import io.github.phongmanh.restaurant.tables.domain.WaiterCall
import io.github.phongmanh.restaurant.tables.domain.WaiterCallId
import io.github.phongmanh.restaurant.tables.domain.WaiterCallKind
import io.github.phongmanh.restaurant.tables.domain.WaiterCallStatus
import io.github.phongmanh.restaurant.tables.persistence.NewWaiterCall
import io.github.phongmanh.restaurant.tables.persistence.WaiterCallRepository
import java.time.Clock
import kotlin.time.toJavaDuration

/**
 * Waiter calls and bill requests (tables §4, FR-008, Should).
 *
 * **Merging:** a tap while the session has an unacknowledged call of the same kind, or one of that
 * kind made less than 60 s ago, returns that call (`200`). So repeated taps make one call, and a
 * retry is harmless without an `Idempotency-Key` (CR-005). Only new calls count towards the limit
 * of 10 per session per hour.
 */
class WaiterCallService(
    private val transactor: Transactor,
    private val calls: WaiterCallRepository,
    private val access: SessionAccess,
    private val limits: ScanLimits,
    private val events: EventLog,
    private val audit: AuditLog,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** The call, and whether this tap created it. */
    suspend fun call(
        guest: Caller.Guest,
        kind: WaiterCallKind,
    ): Pair<WaiterCall, Boolean> {
        val rid = guest.restaurantId
        val sid = guest.sessionId
        val since = { clock.instant().minus(TableRules.MERGE_WINDOW.toJavaDuration()) }
        transactor.inTransaction(rid) { calls.mergeable(this, rid, sid, kind, since()) }?.let { return it to false }
        limits.waiterCall(sid)
        return transactor.inTransaction(rid) {
            // Taps of one session take turns on its row, so the second sees the first's call.
            access.requireOpenForUpdate(this, rid, sid)
            calls.mergeable(this, rid, sid, kind, since())?.let { return@inTransaction it to false }
            val id = WaiterCallId(ids.next())
            val now = clock.instant()
            if (!calls.insertIfNone(this, rid, NewWaiterCall(id, sid, kind, now))) {
                return@inTransaction calls.mergeable(this, rid, sid, kind, since())!! to
                    false
            }
            access.touch(this, rid, sid, now)
            events.append(
                this,
                rid,
                TableEvents.WAITER_CALLED,
                EventSubject(TableEvents.CALL_AGGREGATE, id.value, null),
                EventAudience.STAFF,
            )
            calls.find(this, rid, id)!! to true
        }
    }

    /** The calls of the restaurant's open sessions, oldest first, with the table number. */
    suspend fun list(
        floor: FloorStaff,
        status: WaiterCallStatus,
    ): List<WaiterCall> = transactor.inTransaction(floor.restaurantId) { calls.list(this, floor.restaurantId, status) }

    /**
     * Records who answered and when, and tells every waiter's screen and the guest's
     * (`WaiterCallAcknowledged`). Acknowledging again gives the same result and changes nothing.
     */
    suspend fun acknowledge(
        floor: FloorStaff,
        id: WaiterCallId,
        request: RequestMeta,
    ): WaiterCall =
        transactor.inTransaction(floor.restaurantId) {
            val rid = floor.restaurantId
            val call = calls.find(this, rid, id) ?: throw DomainError.NotFound()
            if (calls.acknowledge(this, rid, id, floor.staffId, clock.instant())) {
                val subject = EventSubject(TableEvents.CALL_AGGREGATE, id.value, null)
                events.append(this, rid, TableEvents.WAITER_CALL_ACKNOWLEDGED, subject, EventAudience.STAFF_AND_SESSION, call.sessionId)
                audit.record(
                    this,
                    AuditEvent(rid, floor.actor, "waiter_call.acknowledged", TableTargets.WAITER_CALL, id.value, request = request),
                )
            }
            calls.find(this, rid, id)!!
        }
}
