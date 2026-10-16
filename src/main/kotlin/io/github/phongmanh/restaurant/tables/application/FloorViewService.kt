package io.github.phongmanh.restaurant.tables.application

import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.tables.domain.DiningTable
import io.github.phongmanh.restaurant.tables.domain.TableRules
import io.github.phongmanh.restaurant.tables.domain.TableSession
import io.github.phongmanh.restaurant.tables.persistence.SessionRepository
import io.github.phongmanh.restaurant.tables.persistence.TableRepository
import io.github.phongmanh.restaurant.tables.persistence.WaiterCallRepository
import java.time.Clock
import kotlin.time.toJavaDuration

/** An occupied table's session on the floor view (FR-032). */
data class FloorSession(
    val session: TableSession,
    val bill: BillSummary,
    val stale: Boolean,
    val openCalls: Int,
)

/** One table of the floor view: free when [session] is `null`. */
data class FloorEntry(
    val table: DiningTable,
    val session: FloorSession?,
)

/**
 * The floor view (tables §6, FR-032): every table by area and number, free or occupied. An occupied
 * table shows its session with the bill summary from `orders` ([SessionSummaries], one batch call
 * for all open sessions) and its open waiter calls. A session with orders that has been open more
 * than 6 h is flagged `stale`, so staff close it (§3.5).
 */
class FloorViewService(
    private val transactor: Transactor,
    private val tables: TableRepository,
    private val sessions: SessionRepository,
    private val calls: WaiterCallRepository,
    private val summaries: SessionSummaries,
    private val clock: Clock,
) {
    suspend fun floor(floor: FloorStaff): List<FloorEntry> =
        transactor.inTransaction(floor.restaurantId) {
            val rid = floor.restaurantId
            val open = sessions.open(this, rid)
            val ids = open.map { it.id }
            val bills = summaries.of(this, rid, ids)
            val openCalls = calls.openCounts(this, rid, ids)
            val staleBefore = clock.instant().minus(TableRules.STALE_AFTER.toJavaDuration())
            val byTable = open.associateBy { it.tableId }
            tables.list(this, rid).sortedWith(TableRules.FLOOR_ORDER).map { table ->
                val session =
                    byTable[table.id]?.let { s ->
                        val bill = bills[s.id] ?: BillSummary(0, 0)
                        FloorSession(s, bill, stale = bill.orderCount > 0 && s.openedAt.isBefore(staleBefore), openCalls[s.id] ?: 0)
                    }
                FloorEntry(table, session)
            }
        }
}
