package io.github.phongmanh.restaurant.staging

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.PrintStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ST-04 (NFR-PERF-04): FR-050's 31-day report on restaurant L's year of `daily_sales` rows (test-plan
 * §6), [SAMPLES] times, spaced so the run stays under `reports.read` (30 a minute per staff member).
 * The first request is timed apart: it may meet a cold instance. Target: p95 ≤ 3 s.
 */
class ReportRun(
    url: String,
    private val out: PrintStream,
) {
    private val base = url.trimEnd('/')

    fun run(planOnly: Boolean): Int {
        val planned = SAMPLES + 2
        out.println("ST-04 on $base: $SAMPLES 31-day reports for restaurant L, one every ${SPACING.toMillis()} ms; $planned requests.")
        if (planOnly) return 0
        val large = loadAccounts().large()
        val api = StagingApi(base, env("VERCEL_AUTOMATION_BYPASS_SECRET"), planned, out)
        val token = LoadSite(api, large).signIn(large.manager)
        val to = LocalDate.now(LOAD_ZONE).minusDays(1)
        val path = "/v1/staff/reports/daily-sales?from=${to.minusDays(REPORT_DAYS - 1)}&to=$to"
        val (first, report) = timed { api.expect(LoadSite.OK, "GET", path, token = token).json }
        val days = report.getValue("days").jsonArray.size
        val orders =
            report
                .getValue("totals")
                .jsonObject
                .getValue("orders")
                .jsonPrimitive.content
        check(days == REPORT_DAYS.toInt()) { "the report answered $days days, not $REPORT_DAYS" }
        val times =
            (1..SAMPLES).map {
                Thread.sleep(SPACING.toMillis())
                timed { api.expect(LoadSite.OK, "GET", path, token = token) }.first
            }
        out.println("[ST-04] first request: $first ms; $days days, $orders orders in the period")
        out.println("[ST-04] ${percentiles(times)} (target p95 ≤ ${TARGET_MS} ms)")
        out.println("ST-04 sent ${api.sent} requests.")
        return if (percentile(times, P95) <= TARGET_MS) 0 else 1
    }

    private companion object {
        const val SAMPLES = 60
        val SPACING: Duration = Duration.ofMillis(2_500)
        const val REPORT_DAYS = 31L
        const val TARGET_MS = 3_000L
    }
}

/**
 * ST-06 (NFR-SCAL-02), scaled: restaurant A goes over its per-restaurant order limit
 * (`order.place_restaurant`, 300 a minute) and gets `429`, while restaurant B's guest keeps ordering
 * in the same minute. A's 25 guests place 10 orders each (each session's own limit) and its
 * manager and two waiters 30 each (each staff member's), [A_ORDERS] in all, from the start of one
 * minute, the limit's fixed window. Then every session closes, which cancels the orders.
 */
class NoisyRun(
    url: String,
    private val out: PrintStream,
) {
    private val base = url.trimEnd('/')

    private class Placed(
        val status: Int,
        val code: String?,
        val retryAfter: String?,
        val at: Instant,
    )

    fun run(planOnly: Boolean): Int {
        out.println(
            "ST-06 on $base: $A_ORDERS orders in A within a minute (its limit is $A_LIMIT), $B_ORDERS in B; about $PLANNED requests.",
        )
        if (planOnly) return 0
        val accounts = loadAccounts()
        val api = StagingApi(base, env("VERCEL_AUTOMATION_BYPASS_SECRET"), PLANNED, out)
        val a = LoadSite(api, accounts.restaurants.getValue("A"))
        val b = LoadSite(api, accounts.restaurants.getValue("B"))
        val aManager = a.signIn(a.seed.manager)
        val aStaff = listOf(aManager) + a.seed.waiters.map { a.signIn(it) }
        val bManager = b.signIn(b.seed.manager)
        val aTables = a.freeTables(aManager)
        check(aTables.size >= A_TABLES) { "A has ${aTables.size} free tables, ST-06 needs $A_TABLES" }
        val aGuests = aTables.take(A_TABLES).map { a.scan(a.qrToken(it.text("id"), aManager)) }
        val bGuest = b.scan(b.qrToken(b.freeTables(bManager).first().text("id"), bManager))
        try {
            val aItem = a.orderableItems(aGuests.first().token, 1).single()
            val bItem = b.orderableItems(bGuest.token, 1).single()
            val calls =
                aGuests.flatMap { guest -> List(SESSION_LIMIT) { Callable { place(api, guest.token, "/v1/guest/orders", aItem) } } } +
                    aStaff.flatMapIndexed { i, token ->
                        List(STAFF_LIMIT) { n ->
                            val session = aGuests[(i * STAFF_LIMIT + n) % aGuests.size].sessionId
                            Callable { place(api, token, "/v1/staff/table-sessions/$session/orders", aItem) }
                        }
                    }
            val windowStart = nextMinute()
            val pool = Executors.newFixedThreadPool(THREADS)
            val aResults = calls.shuffled().map { pool.submit(it) }
            val bResults = (1..B_ORDERS).map { place(api, bGuest.token, "/v1/guest/orders", bItem).also { Thread.sleep(B_SPACING_MS) } }
            val aPlaced = aResults.map { it.get() }
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.MINUTES)
            return report(windowStart, aPlaced, bResults)
        } finally {
            (aGuests.map { it.sessionId }.distinct().map { a to it } + (b to bGuest.sessionId)).forEach { (site, session) ->
                site.close(session, if (site === a) aManager else bManager)
            }
            out.println("ST-06 sent ${api.sent} requests; ${aGuests.size + 1} sessions closed.")
        }
    }

    private fun report(
        windowStart: Instant,
        aPlaced: List<Placed>,
        bPlaced: List<Placed>,
    ): Int {
        val windowEnd = windowStart.plusSeconds(MINUTE_S)
        val late = aPlaced.count { it.at >= windowEnd }
        val accepted = aPlaced.count { it.status == LoadSite.CREATED }
        val limited = aPlaced.filter { it.status == TOO_MANY && it.code == "rate_limited" }
        val other = aPlaced.filter { it.status != LoadSite.CREATED && it !in limited }.groupingBy { "${it.status} ${it.code}" }.eachCount()
        val firstRefused = limited.minOfOrNull { it.at }
        val bAccepted = bPlaced.count { it.status == LoadSite.CREATED }
        val bAfterRefusal = bPlaced.count { it.status == LoadSite.CREATED && firstRefused != null && it.at > firstRefused }
        out.println(
            "[ST-06] A: $accepted of ${aPlaced.size} accepted, ${limited.size} refused with 429 rate_limited " +
                "(Retry-After ${limited.firstOrNull()?.retryAfter} s), other answers $other; $late answered after the window ($windowEnd)",
        )
        out.println("[ST-06] B: $bAccepted of ${bPlaced.size} accepted, $bAfterRefusal of them after A's first refusal ($firstRefused)")
        val pass = accepted <= A_LIMIT && limited.isNotEmpty() && other.isEmpty() && bAccepted == bPlaced.size && bAfterRefusal > 0
        return if (pass) 0 else 1
    }

    private fun place(
        api: StagingApi,
        token: String,
        path: String,
        itemId: String,
    ): Placed {
        val body =
            buildJsonObject {
                putJsonArray("lines") {
                    add(
                        buildJsonObject {
                            put("item_id", itemId)
                            put("quantity", 1)
                        },
                    )
                }
            }
        val response = api.call("POST", path, body, token, mapOf("Idempotency-Key" to UUID.randomUUID().toString()), waitOut429 = false)
        val code = (response.body as? JsonObject)?.get("code")?.jsonPrimitive?.content
        return Placed(response.status, code, response.header("Retry-After"), Instant.now())
    }

    /** Waits for the next minute to start, a second in: the fixed windows begin on the minute (core-and-auth §11). */
    private fun nextMinute(): Instant {
        val now = Instant.now()
        val start = Instant.ofEpochSecond((now.epochSecond / MINUTE_S + 1) * MINUTE_S)
        Thread.sleep(Duration.between(now, start).toMillis() + MARGIN_MS)
        out.println("ST-06's minute starts at $start.")
        return start
    }

    private companion object {
        const val A_TABLES = 25
        const val SESSION_LIMIT = 10
        const val STAFF_LIMIT = 30
        const val A_STAFF = 3
        const val A_ORDERS = A_TABLES * SESSION_LIMIT + A_STAFF * STAFF_LIMIT
        const val A_LIMIT = 300
        const val B_ORDERS = 5
        const val B_SPACING_MS = 6_000L
        const val THREADS = 16
        const val MINUTE_S = 60L
        const val MARGIN_MS = 1_000L
        const val TOO_MANY = 429

        /** Sign-ins, table lists, QR codes and scans, menus, the orders, and the closes. */
        const val PLANNED = 4 + 2 + (A_TABLES + 1) * 2 + 2 + A_ORDERS + B_ORDERS + (A_TABLES + 1) * 3 + 20
    }
}

/** Runs [block] and answers its time in milliseconds with its result. */
private fun <T> timed(block: () -> T): Pair<Long, T> {
    val started = System.nanoTime()
    val result = block()
    return (System.nanoTime() - started) / NANOS_PER_MS to result
}

private fun percentile(
    sorted: List<Long>,
    p: Double,
): Long {
    val values = sorted.sorted()
    return values[((values.size * p).toInt()).coerceAtMost(values.size - 1)]
}

private fun percentiles(times: List<Long>): String =
    "n ${times.size}: p50 ${percentile(times, P50)} · p95 ${percentile(times, P95)} · p99 ${percentile(times, P99)} · max ${times.max()} ms"

private const val NANOS_PER_MS = 1_000_000L
private const val P50 = 0.50
private const val P95 = 0.95
private const val P99 = 0.99

private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content
