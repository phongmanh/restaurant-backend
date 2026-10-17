package io.github.phongmanh.restaurant.reports.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.core.persistence.uuid
import io.github.phongmanh.restaurant.reports.application.ClosedBillSale
import io.github.phongmanh.restaurant.reports.application.ItemSale
import io.github.phongmanh.restaurant.reports.domain.DayRows
import io.github.phongmanh.restaurant.reports.domain.DayTotals
import io.github.phongmanh.restaurant.reports.domain.ItemFigures
import io.github.phongmanh.restaurant.reports.domain.PaymentFigures
import io.github.phongmanh.restaurant.reports.domain.TableFigures
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

/**
 * The daily aggregates (reports §3). Every method takes the restaurant. The ledger's methods
 * upsert: they add to the day's row, creating it on the day's first bill (`ON CONFLICT … DO UPDATE
 * SET x = t.x + excluded.x`), and set the display names to the latest values. The report reads a
 * range; the nightly rebuild reads and replaces a day.
 */
class DailySalesRepository {
    fun addBill(
        tx: TxScope,
        restaurantId: RestaurantId,
        sale: ClosedBillSale,
    ) = update(
        tx,
        "INSERT INTO daily_sales AS t (restaurant_id, sales_date, sessions, orders, gross) VALUES (?, ?, 1, ?, ?) " +
            "ON CONFLICT (restaurant_id, sales_date) DO UPDATE SET sessions = t.sessions + 1, orders = t.orders + excluded.orders, " +
            "gross = t.gross + excluded.gross",
        restaurantId.value,
        sale.businessDate,
        sale.orderCount,
        sale.total,
    )

    fun addPayment(
        tx: TxScope,
        restaurantId: RestaurantId,
        sale: ClosedBillSale,
    ) = update(
        tx,
        "INSERT INTO daily_sales_payment AS t (restaurant_id, sales_date, method, sessions, amount) VALUES (?, ?, ?, 1, ?) " +
            "ON CONFLICT (restaurant_id, sales_date, method) DO UPDATE SET sessions = t.sessions + 1, amount = t.amount + excluded.amount",
        restaurantId.value,
        sale.businessDate,
        sale.paymentMethod,
        sale.total,
    )

    fun addTable(
        tx: TxScope,
        restaurantId: RestaurantId,
        sale: ClosedBillSale,
    ) = update(
        tx,
        "INSERT INTO daily_sales_table AS t (restaurant_id, sales_date, table_id, table_number, sessions, gross) " +
            "VALUES (?, ?, ?, ?, 1, ?) ON CONFLICT (restaurant_id, sales_date, table_id) DO UPDATE SET " +
            "table_number = excluded.table_number, sessions = t.sessions + 1, gross = t.gross + excluded.gross",
        restaurantId.value,
        sale.businessDate,
        sale.tableId.value,
        sale.tableNumber,
        sale.total,
    )

    /** One upsert per item, in the order given (by item ID, so concurrent closes queue). */
    fun addItems(
        tx: TxScope,
        restaurantId: RestaurantId,
        date: LocalDate,
        items: List<ItemSale>,
    ) {
        if (items.isEmpty()) return
        ExposedTransactor
            .connection(tx)
            .prepareStatement(
                "INSERT INTO daily_sales_item AS t (restaurant_id, sales_date, item_id, name_vi, name_en, quantity, revenue) " +
                    "VALUES (?, ?, ?, ?, ?::text, ?, ?) ON CONFLICT (restaurant_id, sales_date, item_id) DO UPDATE SET " +
                    "name_vi = excluded.name_vi, name_en = excluded.name_en, quantity = t.quantity + excluded.quantity, " +
                    "revenue = t.revenue + excluded.revenue",
            ).use { statement ->
                items.forEach {
                    statement.bind(restaurantId.value, date, it.itemId, it.nameVi, it.nameEn, it.quantity, it.revenue).addBatch()
                }
                statement.executeBatch()
            }
    }

    fun addCorrection(
        tx: TxScope,
        restaurantId: RestaurantId,
        date: LocalDate,
        amount: Long,
    ) = update(
        tx,
        "INSERT INTO daily_sales AS t (restaurant_id, sales_date, corrections, correction_count) VALUES (?, ?, ?, 1) " +
            "ON CONFLICT (restaurant_id, sales_date) DO UPDATE SET corrections = t.corrections + excluded.corrections, " +
            "correction_count = t.correction_count + 1",
        restaurantId.value,
        date,
        amount,
    )

    fun addPaymentCorrection(
        tx: TxScope,
        restaurantId: RestaurantId,
        date: LocalDate,
        method: String,
        amount: Long,
    ) = update(
        tx,
        "INSERT INTO daily_sales_payment AS t (restaurant_id, sales_date, method, corrections) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (restaurant_id, sales_date, method) DO UPDATE SET corrections = t.corrections + excluded.corrections",
        restaurantId.value,
        date,
        method,
        amount,
    )

    /**
     * The days of [from] to [to] that have rows, in one statement, so the four tables are read from
     * one snapshot (the S-5 report query: 31 days p95 10 ms). A day's items are only those in its
     * top [top] by quantity or by revenue, ranked as the report ranks them.
     */
    fun range(
        tx: TxScope,
        restaurantId: RestaurantId,
        from: LocalDate,
        to: LocalDate,
        top: Int,
    ): Map<LocalDate, DayRows> {
        val where = "WHERE restaurant_id = ? AND sales_date BETWEEN ? AND ?"
        val sql =
            "SELECT json_build_object(" +
                "'days', (SELECT coalesce(json_agg(json_build_object('date', sales_date, 'sessions', sessions, 'orders', orders, " +
                "'gross', gross, 'corrections', corrections, 'correction_count', correction_count)), '[]') FROM daily_sales $where), " +
                "'payments', (SELECT coalesce(json_agg(json_build_object('date', sales_date, 'method', method, 'sessions', sessions, " +
                "'amount', amount, 'corrections', corrections)), '[]') FROM daily_sales_payment $where), " +
                "'tables', (SELECT coalesce(json_agg(json_build_object('date', sales_date, 'id', table_id, 'number', table_number, " +
                "'sessions', sessions, 'gross', gross)), '[]') FROM daily_sales_table $where), " +
                "'items', (SELECT coalesce(json_agg(json_build_object('date', sales_date, 'id', item_id, 'name_vi', name_vi, " +
                "'name_en', name_en, 'quantity', quantity, 'revenue', revenue)), '[]') FROM (" +
                "SELECT *, row_number() OVER (PARTITION BY sales_date ORDER BY quantity DESC, item_id) AS by_quantity, " +
                "row_number() OVER (PARTITION BY sales_date ORDER BY revenue DESC, item_id) AS by_revenue " +
                "FROM daily_sales_item $where) i WHERE by_quantity <= ? OR by_revenue <= ?)" +
                ")::text"
        // The range once for each of the four tables, then the top for each ranking.
        val params = TABLES.flatMap { listOf(restaurantId.value, from, to) } + listOf(top, top)
        val json =
            ExposedTransactor.connection(tx).prepareStatement(sql).use { statement ->
                statement.bind(params).firstOrNull { it.getString(1) }!!
            }
        val rows = RangeJson.decode(json)
        val dates = rows.days.map { it.date } + rows.payments.map { it.date } + rows.tables.map { it.date } + rows.items.map { it.date }
        return dates.toSortedSet().associate { date ->
            LocalDate.parse(date) to
                DayRows(
                    totals =
                        rows.days.firstOrNull { it.date == date }?.let {
                            DayTotals(
                                it.sessions,
                                it.orders,
                                it.gross,
                                it.corrections,
                                it.correctionCount,
                            )
                        },
                    payments =
                        rows.payments.filter { it.date == date }.associate {
                            it.method to
                                PaymentFigures(it.sessions, it.amount, it.corrections)
                        },
                    tables =
                        rows.tables.filter { it.date == date }.associate {
                            UUID.fromString(it.id) to
                                TableFigures(it.number, it.sessions, it.gross)
                        },
                    items =
                        rows.items.filter { it.date == date }.associate {
                            UUID.fromString(it.id) to ItemFigures(it.nameVi, it.nameEn, it.quantity, it.revenue)
                        },
                )
        }
    }

    /** One day's rows in full, for the rebuild (§5 step 2). */
    fun day(
        tx: TxScope,
        restaurantId: RestaurantId,
        date: LocalDate,
    ): DayRows {
        fun <T> rows(
            sql: String,
            row: (ResultSet) -> T,
        ): List<T> =
            ExposedTransactor.connection(tx).prepareStatement("$sql WHERE restaurant_id = ? AND sales_date = ?").use {
                it.bind(restaurantId.value, date).list(row)
            }
        return DayRows(
            totals =
                rows("SELECT sessions, orders, gross, corrections, correction_count FROM daily_sales") {
                    DayTotals(
                        it.getInt("sessions"),
                        it.getInt("orders"),
                        it.getLong("gross"),
                        it.getLong("corrections"),
                        it.getInt("correction_count"),
                    )
                }.firstOrNull(),
            payments =
                rows("SELECT method, sessions, amount, corrections FROM daily_sales_payment") {
                    it.getString("method") to PaymentFigures(it.getInt("sessions"), it.getLong("amount"), it.getLong("corrections"))
                }.toMap(),
            tables =
                rows("SELECT table_id, table_number, sessions, gross FROM daily_sales_table") {
                    it.uuid("table_id")!! to TableFigures(it.getString("table_number"), it.getInt("sessions"), it.getLong("gross"))
                }.toMap(),
            items =
                rows("SELECT item_id, name_vi, name_en, quantity, revenue FROM daily_sales_item") {
                    it.uuid("item_id")!! to
                        ItemFigures(it.getString("name_vi"), it.getString("name_en"), it.getInt("quantity"), it.getLong("revenue"))
                }.toMap(),
        )
    }

    /**
     * Replaces one day's rows with [rows] (§5 step 3): running it twice gives the same rows. The
     * tables go in the ledger's order, and each table's rows by key, so a close can't deadlock with it.
     */
    fun replaceDay(
        tx: TxScope,
        restaurantId: RestaurantId,
        date: LocalDate,
        rows: DayRows,
    ) {
        val connection = ExposedTransactor.connection(tx)
        TABLES.forEach { table ->
            connection.prepareStatement("DELETE FROM $table WHERE restaurant_id = ? AND sales_date = ?").use {
                it.bind(restaurantId.value, date).executeUpdate()
            }
        }
        rows.totals?.let {
            update(
                tx,
                "INSERT INTO daily_sales (restaurant_id, sales_date, sessions, orders, gross, corrections, correction_count) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                restaurantId.value,
                date,
                it.sessions,
                it.orders,
                it.gross,
                it.corrections,
                it.correctionCount,
            )
        }
        batch(
            tx,
            "INSERT INTO daily_sales_payment (restaurant_id, sales_date, method, sessions, amount, corrections) VALUES (?, ?, ?, ?, ?, ?)",
            rows.payments.toSortedMap().map { (method, p) ->
                listOf(restaurantId.value, date, method, p.sessions, p.amount, p.corrections)
            },
        )
        batch(
            tx,
            "INSERT INTO daily_sales_table (restaurant_id, sales_date, table_id, table_number, sessions, gross) VALUES (?, ?, ?, ?, ?, ?)",
            rows.tables.toSortedMap().map { (id, t) -> listOf(restaurantId.value, date, id, t.number, t.sessions, t.gross) },
        )
        batch(
            tx,
            "INSERT INTO daily_sales_item (restaurant_id, sales_date, item_id, name_vi, name_en, quantity, revenue) " +
                "VALUES (?, ?, ?, ?, ?::text, ?, ?)",
            rows.items.toSortedMap().map { (id, i) -> listOf(restaurantId.value, date, id, i.nameVi, i.nameEn, i.quantity, i.revenue) },
        )
    }

    private companion object {
        /** The four daily tables, in the ledger's lock order (reports §4). */
        val TABLES = listOf("daily_sales", "daily_sales_payment", "daily_sales_table", "daily_sales_item")
    }

    private fun batch(
        tx: TxScope,
        sql: String,
        rows: List<List<Any?>>,
    ) {
        if (rows.isEmpty()) return
        ExposedTransactor.connection(tx).prepareStatement(sql).use { statement ->
            rows.forEach { statement.bind(it).addBatch() }
            statement.executeBatch()
        }
    }

    private fun update(
        tx: TxScope,
        sql: String,
        vararg values: Any?,
    ) {
        ExposedTransactor.connection(tx).prepareStatement(sql).use { it.bind(*values).executeUpdate() }
    }
}

/** The JSON document of [DailySalesRepository.range], one array per table. */
@Serializable
private data class RangeJson(
    val days: List<DayJson>,
    val payments: List<PaymentJson>,
    val tables: List<TableJson>,
    val items: List<ItemJson>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): RangeJson = json.decodeFromString(serializer(), text)
    }
}

@Serializable
private data class DayJson(
    val date: String,
    val sessions: Int,
    val orders: Int,
    val gross: Long,
    val corrections: Long,
    @SerialName("correction_count") val correctionCount: Int,
)

@Serializable
private data class PaymentJson(
    val date: String,
    val method: String,
    val sessions: Int,
    val amount: Long,
    val corrections: Long,
)

@Serializable
private data class TableJson(
    val date: String,
    val id: String,
    val number: String,
    val sessions: Int,
    val gross: Long,
)

@Serializable
private data class ItemJson(
    val date: String,
    val id: String,
    @SerialName("name_vi") val nameVi: String,
    @SerialName("name_en") val nameEn: String? = null,
    val quantity: Int,
    val revenue: Long,
)
