package io.github.phongmanh.restaurant.tables.application

import io.github.phongmanh.restaurant.core.application.Actor
import io.github.phongmanh.restaurant.core.application.AuditEvent
import io.github.phongmanh.restaurant.core.application.AuditLog
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.persistence.tryAdvisoryLock
import io.github.phongmanh.restaurant.tables.domain.CloseReason
import io.github.phongmanh.restaurant.tables.domain.TableRules
import io.github.phongmanh.restaurant.tables.persistence.IdleSessionRepository
import java.time.Clock
import java.time.Instant
import kotlin.time.toJavaDuration

/** What one run of the auto-close job did. */
data class AutoCloseRun(
    val restaurants: Int,
    val closed: Int,
)

/**
 * The hourly job `/internal/jobs/session-auto-close` (tables §3.5, §13 Q2): closes open sessions
 * without orders after 2 h without activity, so the next guests at that table don't join the last
 * guests' bill. A session with orders is never closed here, because closing settles money (FR-013).
 *
 * A platform transaction finds the restaurants with idle sessions; each restaurant's sessions are
 * then closed in its own transaction, which holds the job's lock for that restaurant
 * (`pg_try_advisory_xact_lock`), so two runs never close the same sessions. Each session is locked
 * `FOR UPDATE` (rows another transaction holds are skipped this run), then `EmptyBills.closeIfEmpty`
 * decides, so an order placed at that moment keeps the session open. Batches are bounded; what's
 * left waits for the next run.
 */
class SessionAutoClose(
    private val transactor: Transactor,
    private val sessions: IdleSessionRepository,
    private val access: SessionAccess,
    private val emptyBills: EmptyBills,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    suspend fun run(): AutoCloseRun {
        val idleBefore: Instant = clock.instant().minus(TableRules.IDLE_CLOSE_AFTER.toJavaDuration())
        val restaurants =
            transactor.platformTransaction("session auto-close: restaurants with idle sessions") {
                sessions.idleRestaurants(this, idleBefore, RESTAURANTS_PER_RUN)
            }
        val closed =
            restaurants.sumOf { rid ->
                transactor.inTransaction(rid) {
                    if (!tryAdvisoryLock(this, "job:session-auto-close:$rid")) return@inTransaction 0
                    val now = clock.instant()
                    sessions.lockIdle(this, rid, idleBefore, SESSIONS_PER_RESTAURANT).count { sid ->
                        val closes =
                            emptyBills.closeIfEmpty(this, rid, sid) && access.close(this, rid, sid, CloseReason.ABANDONED_EMPTY, now)
                        if (closes) {
                            audit.record(
                                this,
                                AuditEvent(rid, Actor.SYSTEM, "table_session.auto_closed", TableTargets.SESSION, sid.value, request = null),
                            )
                        }
                        closes
                    }
                }
            }
        return AutoCloseRun(restaurants.size, closed)
    }

    private companion object {
        const val RESTAURANTS_PER_RUN = 500
        const val SESSIONS_PER_RESTAURANT = 100
    }
}
