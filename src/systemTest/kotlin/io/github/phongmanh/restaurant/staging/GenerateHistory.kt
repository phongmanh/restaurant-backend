package io.github.phongmanh.restaurant.staging

import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.reports.application.SalesFacts
import io.github.phongmanh.restaurant.reports.application.SalesSource
import io.github.phongmanh.restaurant.reports.application.tally
import io.github.phongmanh.restaurant.reports.domain.DayRows
import io.github.phongmanh.restaurant.reports.persistence.DailySalesRepository
import io.github.phongmanh.restaurant.staging.StaffFlows.Companion.CREATED
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.koin.core.Koin
import java.io.File
import java.io.PrintStream
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.system.exitProcess

/**
 * Writes test-plan §6's scaled history, after `seedStaging`:
 *
 * - **restaurant L**, the largest: 1,000 menu items and 25 tables through the API; its last
 *   [RAW_DAYS] days as real sessions, bills, orders and lines, and a full year of daily sales rows;
 * - **[SMALL_COUNT] smaller restaurants**, onboarded through the API, each with a year of daily
 *   sales rows for 100 items, so the report's indexes hold many restaurants.
 *
 * The daily rows always come from the service's own `tally`, so they add up as a close would have
 * written them. The days that keep their bills are written with `replaceDay` from those bills, so
 * the nightly `sales-reconcile`, which rebuilds the last two days, finds nothing to fix. The smaller
 * restaurants' rows stop [SMALL_GAP_DAYS] days back, before that job's window. The run prints its
 * size estimate first and refuses more than [MAX_MB] MB, within Neon's free 0.5 GB.
 *
 * `./gradlew generateHistory --args="https://restaurant-staging-….vercel.app"`, with the environment
 * exported as for `migrate` (deploy/README.md).
 */
fun main(args: Array<String>) {
    val url = args.singleOrNull()
    if (url == null || !url.startsWith("https://")) {
        println("usage: generateHistory --args=\"<deployment URL>\"")
        exitProcess(2)
    }
    val code =
        runCatching { GenerateHistory(url, System.out).run() }.fold(
            onSuccess = { 0 },
            onFailure = {
                println("history stopped: ${it.message}")
                1
            },
        )
    exitProcess(code)
}

class GenerateHistory(
    url: String,
    private val out: PrintStream,
    private val file: File = File(Accounts.PATH),
    private val today: LocalDate = LocalDate.now(ZONE),
) {
    private val api = StagingApi(url, env("VERCEL_AUTOMATION_BYPASS_SECRET"), BUDGET, out)
    private val flows = StaffFlows(api)
    private val random = SecureRandom()

    fun run() {
        check(file.exists()) { "${file.path} is missing: run seedStaging first" }
        var accounts = Accounts.read(file)
        check(accounts.history == null) {
            if (accounts.history?.complete == true) {
                "the history exists already"
            } else {
                "an earlier run stopped part-way; reset the staging database before running again"
            }
        }
        val estimate = estimateMb()
        out.println("History: restaurant L ($RAW_DAYS days of bills, $LEDGER_DAYS days of sales) and $SMALL_COUNT smaller restaurants.")
        out.println("Estimated size ≈ $estimate MB (at most $MAX_MB); about $PLANNED requests (at most $BUDGET).")
        check(estimate <= MAX_MB) { "the estimate is over $MAX_MB MB" }

        val admin = flows.signIn(accounts.admin.email, accounts.admin.password, Authenticator(accounts.admin.totpSecret))
        val large = onboardLarge(admin)
        val smallIds = (1..SMALL_COUNT).map { onboard(admin, "Quán nhỏ %02d (staging)".format(it), "small%02d".format(it)).first }
        accounts = accounts.copy(history = History(large, smallIds, complete = false))
        accounts.write(file)
        out.println("Restaurants onboarded through the API (${api.sent} requests). Writing the history rows…")

        withService { koin -> writeAll(koin, large, smallIds) }
        accounts.copy(history = History(large, smallIds, complete = true)).write(file)
        out.println("Done. Restaurant L is ${large.id}; its manager is in ${file.path}.")
    }

    private fun writeAll(
        koin: Koin,
        large: SeededRestaurant,
        smallIds: List<String>,
    ) {
        val transactor = koin.get<Transactor>()
        val writer = HistoryWriter(koin.get<SalesSource>(), koin.get<DailySalesRepository>())

        // Daily rows only, for days whose bills aren't kept, CHUNK_DAYS days to a transaction.
        fun ledger(
            rid: RestaurantId,
            plan: HistoryPlan,
            seed: Long,
            daysBack: IntRange,
        ) = daysBack.chunked(CHUNK_DAYS).forEach { chunk ->
            val days =
                chunk.associate { back ->
                    today.minusDays(back.toLong()).let {
                        it to
                            tally(SalesFacts(plan.day(it, seed).map(PlanBill::sale), emptyList()))
                    }
                }
            runBlocking { transactor.inTransaction(rid) { writer.insertDays(this, rid, days) } }
        }

        val rid = RestaurantId(UUID.fromString(large.id))
        val (items, tables) = runBlocking { transactor.inTransaction(rid) { writer.menuAndTables(this, rid) } }
        val plan = HistoryPlan(items, tables, LARGE_BILLS)
        val managerId = UUID.fromString(large.manager.staffId)
        for (back in RAW_DAYS downTo 1) {
            val date = today.minusDays(back.toLong())
            val bills = plan.day(date, LARGE_SEED)
            runBlocking { transactor.inTransaction(rid) { writer.rawDay(this, rid, date, bills, managerId) } }
        }
        out.println("L: $RAW_DAYS days of bills written; daily rows rebuilt from them.")
        ledger(rid, plan, LARGE_SEED, (RAW_DAYS + 1)..LEDGER_DAYS)
        out.println("L: ${LEDGER_DAYS - RAW_DAYS} older days of daily rows written.")

        smallIds.forEachIndexed { i, id ->
            ledger(
                RestaurantId(UUID.fromString(id)),
                HistoryPlan(syntheticItems(i), syntheticTables(), SMALL_BILLS),
                SMALL_SEED + i,
                SMALL_GAP_DAYS..LEDGER_DAYS,
            )
            if ((i + 1) % PROGRESS_EVERY == 0) out.println("Smaller restaurants: ${i + 1} of $SMALL_COUNT written.")
        }
    }

    private fun onboardLarge(admin: String): SeededRestaurant {
        val (id, manager) = onboard(admin, "Nhà hàng L (staging, lớn nhất)", "large")
        val password = newPassword(random)
        flows.acceptInvitation(manager.second, password)
        val token = flows.signIn(manager.first.email, password)
        val itemIds =
            (0 until LARGE_CATEGORIES).flatMap { c ->
                val categoryId =
                    api
                        .expect(
                            CREATED,
                            "POST",
                            "/v1/staff/menu/categories",
                            buildJsonObject {
                                put("name_vi", "Nhóm ${c + 1}")
                                put("name_en", "Group ${c + 1}")
                            },
                            token,
                        ).text("id")
                (0 until ITEMS_PER_CATEGORY).map { n ->
                    val number = c * ITEMS_PER_CATEGORY + n + 1
                    api
                        .expect(
                            CREATED,
                            "POST",
                            "/v1/staff/menu/items",
                            buildJsonObject {
                                put("category_id", categoryId)
                                put("name_vi", "Món L %04d".format(number))
                                put("name_en", "Dish L %04d".format(number))
                                putJsonObject("price") {
                                    put("amount", (MIN_PRICE + (number * PRICE_STEP) % PRICE_SPREAD).toString())
                                    put("currency", "VND")
                                }
                            },
                            token,
                        ).text("id")
                }
            }
        val tableIds =
            (1..LARGE_TABLES).map { n ->
                api.expect(CREATED, "POST", "/v1/staff/tables", buildJsonObject { put("number", "L$n") }, token).text("id")
            }
        return SeededRestaurant(
            id = id,
            name = "Nhà hàng L (staging, lớn nhất)",
            manager = manager.first.copy(password = password),
            tableIds = tableIds,
            itemIds = itemIds,
        )
    }

    /** Onboards a restaurant; returns its ID, its manager and the manager's invitation token. */
    private fun onboard(
        admin: String,
        name: String,
        handle: String,
    ): Pair<String, Pair<Login, String>> {
        val body =
            buildJsonObject {
                put("name", name)
                put("address", "$handle, TP. Hồ Chí Minh")
                put("time_zone", ZONE.id)
                put("languages", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("vi"))))
                putJsonObject("first_manager") {
                    put("email", "manager.$handle@staging.example")
                    put("display_name", "Quản lý $handle")
                }
            }
        val created =
            api
                .expect(CREATED, "POST", "/v1/admin/restaurants", body, admin, mapOf("Idempotency-Key" to UUID.randomUUID().toString()))
                .json
        val manager = created.getValue("first_manager").jsonObject
        val login = Login(manager.getValue("email").jsonPrimitive.content, staffId = manager.getValue("id").jsonPrimitive.content)
        return created
            .getValue("restaurant")
            .jsonObject
            .getValue("id")
            .jsonPrimitive.content to
            (login to flows.tokenOf(created.getValue("invitation").jsonObject))
    }

    private fun syntheticItems(restaurant: Int): List<PlanItem> =
        (1..SMALL_ITEMS).map {
            PlanItem(UuidV7.next(), "Món %02d-%03d".format(restaurant + 1, it), null, MIN_PRICE + (it * PRICE_STEP) % PRICE_SPREAD)
        }

    private fun syntheticTables(): List<PlanTable> = (1..LARGE_TABLES).map { PlanTable(UuidV7.next(), "$it") }

    /**
     * The size from the plan itself: the days are deterministic, so they are made in memory, added up
     * with `tally` and counted. S-2 Q7: ≈ 738 bytes per order with its bill and lines; ≈ 190 bytes per
     * daily row with its primary key. Counts depend on item and table positions only, so stand-ins do.
     */
    private fun estimateMb(): Long {
        fun rowsOf(bills: List<PlanBill>) =
            tally(SalesFacts(bills.map(PlanBill::sale), emptyList())).let {
                1L + it.payments.size +
                    it.tables.size +
                    it.items.size
            }
        val largePlan = HistoryPlan(standInItems(LARGE_CATEGORIES * ITEMS_PER_CATEGORY), standInTables(), LARGE_BILLS)
        var orders = 0L
        var rows = 0L
        for (back in 1..LEDGER_DAYS) {
            val bills = largePlan.day(today.minusDays(back.toLong()), LARGE_SEED)
            rows += rowsOf(bills)
            if (back <= RAW_DAYS) orders += bills.sumOf { it.orders.size }
        }
        repeat(SMALL_COUNT) { i ->
            val plan = HistoryPlan(standInItems(SMALL_ITEMS), standInTables(), SMALL_BILLS)
            for (back in SMALL_GAP_DAYS..LEDGER_DAYS) rows += rowsOf(plan.day(today.minusDays(back.toLong()), SMALL_SEED + i))
        }
        out.println("Plan: $orders orders with their bills and lines, $rows daily rows.")
        return (orders * BYTES_PER_ORDER + rows * BYTES_PER_ROW) / BYTES_PER_MB
    }

    private fun standInItems(count: Int) = (1..count).map { PlanItem(UUID(0, it.toLong()), "-", null, MIN_PRICE) }

    private fun standInTables() = (1..LARGE_TABLES).map { PlanTable(UUID(1, it.toLong()), "$it") }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
        const val RAW_DAYS = 30
        const val LEDGER_DAYS = 365
        const val SMALL_COUNT = 49

        /** Before the reconcile's window (yesterday and the day before, reports §5). */
        const val SMALL_GAP_DAYS = 3
        val LARGE_BILLS = 270..330

        /**
         * A small eatery's day. They exist to put many restaurants into the report's indexes: at 40–80
         * bills a day nearly every item and table gets a row each day, and the plan came to 312 MB.
         */
        val SMALL_BILLS = 15..30
        const val LARGE_CATEGORIES = 20
        const val ITEMS_PER_CATEGORY = 50
        const val LARGE_TABLES = 25
        const val SMALL_ITEMS = 100
        const val LARGE_SEED = 7L
        const val SMALL_SEED = 1_000L
        const val MIN_PRICE = 20_000L
        const val PRICE_STEP = 5_000L
        const val PRICE_SPREAD = 280_000L
        const val CHUNK_DAYS = 30
        const val PROGRESS_EVERY = 7
        const val MAX_MB = 300L
        const val BYTES_PER_ORDER = 738L

        /** Measured on staging, 2026-10-17: `daily_sales_item` took 189 MB for 1.0M rows. */
        const val BYTES_PER_ROW = 190L
        const val BYTES_PER_MB = 1_000_000L
        const val PLANNED = 1_100
        const val BUDGET = 1_500
    }
}

/** The history's SQL, in the restaurant's own transaction (RLS applies, as for any tenant write). */
class HistoryWriter(
    private val source: SalesSource,
    private val sales: DailySalesRepository,
) {
    fun menuAndTables(
        tx: TxScope,
        rid: RestaurantId,
    ): Pair<List<PlanItem>, List<PlanTable>> {
        val c = ExposedTransactor.connection(tx)
        val items =
            query(c, ITEMS_SQL, rid) {
                PlanItem(it.getObject("id", UUID::class.java), it.getString("name_vi"), it.getString("name_en"), it.getLong("price"))
            }
        val tables = query(c, TABLES_SQL, rid) { PlanTable(it.getObject("id", UUID::class.java), it.getString("number")) }
        return items to tables
    }

    /** One day's bills as real rows, then that day's daily rows rebuilt from them as the nightly job would. */
    fun rawDay(
        tx: TxScope,
        rid: RestaurantId,
        date: LocalDate,
        bills: List<PlanBill>,
        managerId: UUID,
    ) {
        val sessions = mutableListOf<List<Any?>>()
        val billRows = mutableListOf<List<Any?>>()
        val orders = mutableListOf<List<Any?>>()
        val lines = mutableListOf<List<Any?>>()
        bills.forEach { bill ->
            val sessionId = UuidV7.next()
            sessions += listOf(sessionId, rid.value, bill.table.id, bill.openedAt, bill.closedAt, bill.closedAt, managerId)
            billRows +=
                listOf(
                    sessionId,
                    rid.value,
                    bill.orders.size,
                    bill.total,
                    bill.paymentMethod,
                    bill.total,
                    bill.closedAt,
                    managerId,
                    date,
                    bill.openedAt,
                )
            bill.orders.forEach { order ->
                val orderId = UuidV7.next()
                orders +=
                    listOf(
                        orderId,
                        rid.value,
                        sessionId,
                        order.placedAt,
                        order.total,
                        order.placedAt.plusSeconds(SERVED_AFTER_S),
                        managerId,
                    )
                order.lines.forEachIndexed { n, l ->
                    lines +=
                        listOf(
                            UuidV7.next(),
                            rid.value,
                            orderId,
                            n + 1,
                            l.item.id,
                            l.item.nameVi,
                            l.item.nameEn,
                            l.item.price,
                            l.item.price,
                            l.quantity,
                            l.total,
                        )
                }
            }
        }
        val c = ExposedTransactor.connection(tx)
        batch(c, SESSION_SQL, sessions)
        batch(c, BILL_SQL, billRows)
        batch(c, ORDER_SQL, orders)
        batch(c, LINE_SQL, lines)
        rebuildDay(tx, rid, date, bills)
    }

    /** The day's daily rows from the bills just written, through the same source and tally as `sales-reconcile`. */
    private fun rebuildDay(
        tx: TxScope,
        rid: RestaurantId,
        date: LocalDate,
        bills: List<PlanBill>,
    ) {
        val rows = tally(source.facts(tx, rid, date))
        val totals = rows.totals
        check(totals != null && totals.sessions == bills.size && totals.gross == bills.sumOf { it.total }) {
            "the rebuilt day $date doesn't match the bills written"
        }
        sales.replaceDay(tx, rid, date, rows)
    }

    /** Daily rows for days that have none yet, batched per table, in the columns `replaceDay` writes. */
    fun insertDays(
        tx: TxScope,
        rid: RestaurantId,
        days: Map<LocalDate, DayRows>,
    ) {
        val c = ExposedTransactor.connection(tx)
        val r = rid.value
        batch(
            c,
            DAY_SQL,
            days.mapNotNull { (d, rows) ->
                rows.totals?.let { listOf(r, d, it.sessions, it.orders, it.gross, it.corrections, it.correctionCount) }
            },
        )
        batch(
            c,
            PAYMENT_SQL,
            days.flatMap { (d, rows) ->
                rows.payments.map { (m, p) -> listOf(r, d, m, p.sessions, p.amount, p.corrections) }
            },
        )
        batch(c, TABLE_SQL, days.flatMap { (d, rows) -> rows.tables.map { (id, t) -> listOf(r, d, id, t.number, t.sessions, t.gross) } })
        batch(
            c,
            ITEM_SQL,
            days.flatMap { (d, rows) ->
                rows.items.map { (id, i) -> listOf(r, d, id, i.nameVi, i.nameEn, i.quantity, i.revenue) }
            },
        )
    }

    private fun batch(
        c: Connection,
        sql: String,
        rows: List<List<Any?>>,
    ) {
        if (rows.isEmpty()) return
        c.prepareStatement(sql).use { statement ->
            rows.forEach { statement.bind(it).addBatch() }
            statement.executeBatch()
        }
    }

    private fun <T> query(
        c: Connection,
        sql: String,
        rid: RestaurantId,
        row: (ResultSet) -> T,
    ): List<T> = c.prepareStatement(sql).use { it.bind(rid.value).list(row) }

    private companion object {
        const val ITEMS_SQL =
            "SELECT id, name_vi, name_en, price FROM menu_items WHERE restaurant_id = ? AND deleted_at IS NULL ORDER BY display_order, id"
        const val TABLES_SQL = "SELECT id, number FROM dining_tables WHERE restaurant_id = ? AND deleted_at IS NULL ORDER BY created_at, id"

        /** A guest opened it (no `opened_by`); the manager closed it, paid. */
        const val SESSION_SQL =
            "INSERT INTO table_sessions (id, restaurant_id, table_id, status, opened_at, last_activity_at, closed_at, closed_by, " +
                "close_reason) VALUES (?, ?, ?, 'closed', ?, ?, ?, ?, 'paid')"
        const val BILL_SQL =
            "INSERT INTO bills (session_id, restaurant_id, status, order_count, total, payment_method, amount_received, closed_at, " +
                "closed_by, business_date, created_at) VALUES (?, ?, 'closed', ?, ?, ?, ?, ?, ?, ?, ?)"
        const val ORDER_SQL =
            "INSERT INTO orders (id, restaurant_id, session_id, status, placed_at, total, status_changed_at, status_changed_by) " +
                "VALUES (?, ?, ?, 'served', ?, ?, ?, ?)"
        const val LINE_SQL =
            "INSERT INTO order_lines (id, restaurant_id, order_id, line_no, item_id, name_vi, name_en, unit_price, unit_total, " +
                "quantity, line_total) VALUES (?, ?, ?, ?, ?, ?, ?::text, ?, ?, ?, ?)"
        const val DAY_SQL =
            "INSERT INTO daily_sales (restaurant_id, sales_date, sessions, orders, gross, corrections, correction_count) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)"
        const val PAYMENT_SQL =
            "INSERT INTO daily_sales_payment (restaurant_id, sales_date, method, sessions, amount, corrections) VALUES (?, ?, ?, ?, ?, ?)"
        const val TABLE_SQL =
            "INSERT INTO daily_sales_table (restaurant_id, sales_date, table_id, table_number, sessions, gross) VALUES (?, ?, ?, ?, ?, ?)"
        const val ITEM_SQL =
            "INSERT INTO daily_sales_item (restaurant_id, sales_date, item_id, name_vi, name_en, quantity, revenue) " +
                "VALUES (?, ?, ?, ?, ?::text, ?, ?)"

        /** Served 5 minutes after it was placed: the last order is at most 33 minutes in, and a stay is at least 40. */
        const val SERVED_AFTER_S = 300L
    }
}
