package io.github.phongmanh.restaurant.tables.domain

import io.github.phongmanh.restaurant.core.domain.TableId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@JvmInline
value class WaiterCallId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

/**
 * A dining table (tables §5, FR-030). [qrKeyVersion] is the version its printed code must carry
 * (§2). [deleted] is only ever `true` on a read that asks for deleted tables too.
 */
data class DiningTable(
    val id: TableId,
    val number: String,
    val area: String?,
    val seats: Int?,
    val enabled: Boolean,
    val qrKeyVersion: Int,
    val version: Int,
    val deleted: Boolean = false,
) {
    /** A session may open or move here (§3.1, §3.4): enabled and not deleted. */
    val available: Boolean get() = enabled && !deleted
}

data class NewTable(
    val number: String,
    val area: String?,
    val seats: Int?,
)

/** A table edit. [area] `""` removes the area; `null` fields stay as they are. */
data class TableChanges(
    val number: String?,
    val area: String?,
    val seats: Int?,
    val enabled: Boolean?,
) {
    /** The names of the fields the edit sets, for the audit entry (no values: they're free text). */
    val fields: List<String>
        get() = listOfNotNull(number?.let { "number" }, area?.let { "area" }, seats?.let { "seats" }, enabled?.let { "enabled" })
}

enum class SessionStatus {
    OPEN,
    CLOSED,
    ;

    val value: String get() = name.lowercase(Locale.ROOT)

    companion object {
        fun of(value: String): SessionStatus = valueOf(value.uppercase(Locale.ROOT))
    }
}

/** Why a session closed (tables §5): settled, closed empty by the job (§3.5), or closed by staff with nothing to pay. */
enum class CloseReason {
    PAID,
    ABANDONED_EMPTY,
    CLOSED_BY_STAFF,
    ;

    val value: String get() = name.lowercase(Locale.ROOT)
}

/** A table session (tables §3) with the number of the table it's at now. */
data class TableSession(
    val id: TableSessionId,
    val tableId: TableId,
    val tableNumber: String,
    val status: SessionStatus,
    val openedAt: Instant,
    val lastActivityAt: Instant,
    val closedAt: Instant?,
    val version: Int,
)

enum class WaiterCallKind {
    CALL_WAITER,
    REQUEST_BILL,
    ;

    val value: String get() = name.lowercase(Locale.ROOT)

    companion object {
        fun of(value: String?): WaiterCallKind? = entries.firstOrNull { it.value == value }
    }
}

enum class WaiterCallStatus {
    OPEN,
    ACKNOWLEDGED,
    CLOSED_WITH_SESSION,
    ;

    val value: String get() = name.lowercase(Locale.ROOT)

    companion object {
        fun of(value: String): WaiterCallStatus = valueOf(value.uppercase(Locale.ROOT))
    }
}

/** A waiter call or bill request (tables §4, FR-008), with the table its session is at. */
data class WaiterCall(
    val id: WaiterCallId,
    val sessionId: TableSessionId,
    val kind: WaiterCallKind,
    val status: WaiterCallStatus,
    val createdAt: Instant,
    val acknowledgedAt: Instant?,
    val tableId: TableId,
    val tableNumber: String,
)

/** The limits and times of the tables design. */
object TableRules {
    /** ASSUMPTION-4 (§5): more → `409 table_limit_reached`. */
    const val MAX_TABLES = 300
    const val NUMBER_MAX = 20
    const val AREA_MAX = 50
    const val SEATS_MIN = 1
    const val SEATS_MAX = 50

    /** A tap of the same kind within this time returns the last call (FR-008, §4). */
    val MERGE_WINDOW: Duration = 60.seconds

    /** An empty session idle this long is closed by the job (§3.5, §13 Q2). */
    val IDLE_CLOSE_AFTER: Duration = 2.hours

    /** A session with orders open this long is flagged `stale` in the floor view (§3.5). */
    val STALE_AFTER: Duration = 6.hours

    fun number(value: String): Boolean = value.length in 1..NUMBER_MAX

    fun area(value: String): Boolean = value.length <= AREA_MAX

    fun seats(value: Int): Boolean = value in SEATS_MIN..SEATS_MAX

    /**
     * The floor view's order (§6): by area (tables without one last), then by number, with digit
     * runs compared as numbers so table 2 comes before table 10.
     */
    val FLOOR_ORDER: Comparator<DiningTable> =
        compareBy<DiningTable, String?>(nullsLast()) { it.area?.lowercase(Locale.ROOT) }
            .then { a, b -> compareNatural(a.number, b.number) }
            .thenBy { it.id.value }

    private val CHUNK = Regex("\\d+|\\D+")

    private fun compareNatural(
        a: String,
        b: String,
    ): Int {
        val left = CHUNK.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val right = CHUNK.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val c =
                if (x[0].isDigit() && y[0].isDigit()) {
                    compareValuesBy(x, y, { it.trimStart('0').length }, { it.trimStart('0') })
                } else {
                    x.compareTo(y)
                }
            if (c != 0) return c
        }
        return left.size.compareTo(right.size)
    }
}
