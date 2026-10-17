package io.github.phongmanh.restaurant.staging

import io.github.phongmanh.restaurant.core.domain.TableId
import io.github.phongmanh.restaurant.reports.application.ClosedBillSale
import io.github.phongmanh.restaurant.reports.application.ItemSale
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

/** A menu item as the history uses it: its ID, its name snapshot and its price. */
data class PlanItem(
    val id: UUID,
    val nameVi: String,
    val nameEn: String?,
    val price: Long,
)

data class PlanTable(
    val id: UUID,
    val number: String,
)

data class PlanLine(
    val item: PlanItem,
    val quantity: Int,
) {
    val total: Long get() = item.price * quantity
}

data class PlanOrder(
    val placedAt: Instant,
    val lines: List<PlanLine>,
) {
    val total: Long get() = lines.sumOf { it.total }
}

/** One paid bill: a table session from [openedAt] to [closedAt], all on [date] in the restaurant's zone. */
data class PlanBill(
    val date: LocalDate,
    val table: PlanTable,
    val openedAt: Instant,
    val closedAt: Instant,
    val paymentMethod: String,
    val orders: List<PlanOrder>,
) {
    val total: Long get() = orders.sumOf { it.total }

    /** The bill as the close hands it to the sales ledger (reports §4), so `tally` adds it up as the service would. */
    fun sale(): ClosedBillSale =
        ClosedBillSale(
            businessDate = date,
            tableId = TableId(table.id),
            tableNumber = table.number,
            paymentMethod = paymentMethod,
            total = total,
            orderCount = orders.size,
            items =
                orders.flatMap { it.lines }.map { ItemSale(it.item.id, it.item.nameVi, it.item.nameEn, it.quantity, it.total) },
        )
}

/**
 * Makes a restaurant's synthetic bills for a day (test-plan §6): lunch and dinner peaks, one to three
 * orders a bill, popular items ordered most. The same seed always gives the same day, so a figure
 * can be checked again later.
 */
class HistoryPlan(
    private val items: List<PlanItem>,
    private val tables: List<PlanTable>,
    private val billsPerDay: IntRange,
    private val zone: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh"),
) {
    init {
        require(items.isNotEmpty() && tables.isNotEmpty())
    }

    fun day(
        date: LocalDate,
        seed: Long,
    ): List<PlanBill> {
        val random = Random(seed * DAY_SEED + date.toEpochDay())
        return (1..random.nextInt(billsPerDay.first, billsPerDay.last + 1)).map { bill(date, random) }.sortedBy { it.closedAt }
    }

    private fun bill(
        date: LocalDate,
        random: Random,
    ): PlanBill {
        val lunch = random.nextInt(PERCENT) < LUNCH_SHARE
        val start = if (lunch) LUNCH_FROM else DINNER_FROM
        val window = if (lunch) LUNCH_MINUTES else DINNER_MINUTES
        val opened = date.atTime(start.plusMinutes(random.nextLong(window))).atZone(zone).toInstant()
        val minutes = random.nextLong(MIN_STAY, MAX_STAY)
        val closed = opened.plusSeconds(minutes * SECONDS)
        val orderCount =
            random.nextInt(PERCENT).let {
                when {
                    it < ONE_ORDER -> 1
                    it < TWO_ORDERS -> 2
                    else -> MAX_ORDERS
                }
            }
        val orders =
            (0 until orderCount).map { n ->
                val placed = opened.plusSeconds((ORDER_GAP_MIN * n + random.nextLong(ORDER_JITTER_MIN)) * SECONDS)
                PlanOrder(placed, (1..random.nextInt(1, MAX_LINES + 1)).map { line(random) }.distinctBy { it.item.id })
            }
        return PlanBill(date, tables[random.nextInt(tables.size)], opened, closed, payment(random), orders)
    }

    /** Popular items first: the cube of a uniform number leans hard towards the start of the menu. */
    private fun line(random: Random): PlanLine {
        val r = random.nextDouble()
        val item = items[(r * r * r * items.size).toInt().coerceAtMost(items.size - 1)]
        return PlanLine(item, if (random.nextInt(PERCENT) < SINGLE_SHARE) 1 else random.nextInt(2, MAX_QUANTITY + 1))
    }

    private fun payment(random: Random): String =
        random.nextInt(PERCENT).let {
            when {
                it < CASH_SHARE -> "cash"
                it < CASH_SHARE + CARD_SHARE -> "card"
                else -> "bank_transfer"
            }
        }

    private companion object {
        const val DAY_SEED = 100_003L
        const val PERCENT = 100
        const val LUNCH_SHARE = 40
        val LUNCH_FROM: LocalTime = LocalTime.of(10, 30)
        val DINNER_FROM: LocalTime = LocalTime.of(17, 0)
        const val LUNCH_MINUTES = 180L
        const val DINNER_MINUTES = 270L

        /** The latest dinner opens at 21:30 and stays at most 100 minutes, so every bill closes on its day. */
        const val MIN_STAY = 40L
        const val MAX_STAY = 101L
        const val SECONDS = 60L
        const val ONE_ORDER = 50
        const val TWO_ORDERS = 85
        const val MAX_ORDERS = 3
        const val ORDER_GAP_MIN = 12L
        const val ORDER_JITTER_MIN = 10L
        const val MAX_LINES = 4
        const val SINGLE_SHARE = 75
        const val MAX_QUANTITY = 3
        const val CASH_SHARE = 50
        const val CARD_SHARE = 25
    }
}
