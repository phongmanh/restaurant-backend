package io.github.phongmanh.restaurant.staging

import io.github.phongmanh.restaurant.core.config.AppConfig
import io.github.phongmanh.restaurant.core.persistence.JdbcTarget
import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.PrintStream
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/** A write k6 saw acknowledged: the order's [id] at [version], in [sessionId], at k6's [at]. */
class Wrote(
    val at: Instant,
    val sessionId: String,
    val id: String,
    val version: Int,
    val received: Instant,
)

/**
 * Test-plan ST-03 and ST-10 in release 1 (CR-013), scaled: restaurant L's screens poll the event log
 * as realtime §7 tells the apps to, while k6 runs the smoke load. Two phones at each table (the k6
 * guest's and one more scan) read `/v1/guest/events` every 60 s, and [STAFF_SCREENS] staff screens
 * read `/v1/staff/events` every 15 s, each ±20 % from a random point of its timer.
 *
 * - **ST-03:** for each order write k6 saw acknowledged, the time until it first shows on each screen
 *   that sees it (staff: all; a phone: its session's), against NFR-PERF-03's release-1 target. A
 *   sample is left out when its screen was paused in between: a phone asleep shows nothing.
 * - **ST-10:** half the screens pause once, at random, for 1 to 3 minutes (a phone asleep, a closed
 *   tab), then catch up at once, as the apps do on returning to the foreground. At the end each screen
 *   catches up once more, and its events must be exactly the database's for its view: every `seq`,
 *   once, in order, with the same type and order. And with live push off, the run adds no outbox row.
 */
class LiveScreens(
    private val api: StagingApi,
    private val site: LoadSite,
    private val out: PrintStream,
    private val dir: File,
) {
    private val started = Instant.now()
    private val screens = mutableListOf<Screen>()
    private val staffToken = StaffToken()

    /** Opens the screens: the k6 guests' phones, one more phone a table, and the staff screens. */
    fun start(guests: List<LoadGuest>) {
        val tables = guests.distinctBy { it.sessionId }
        tables.forEachIndexed { i, guest ->
            val second = site.scan(guest.qrToken)
            check(second.sessionId == guest.sessionId) { "the second phone at a table joined another session" }
            screens += Screen("guest-${i + 1}a", guest.sessionId, GUEST_PATH, GUEST_POLL) { guest.token }
            screens += Screen("guest-${i + 1}b", guest.sessionId, GUEST_PATH, GUEST_POLL) { second.token }
        }
        repeat(STAFF_SCREENS) { screens += Screen("staff-${it + 1}", null, STAFF_PATH, STAFF_POLL, staffToken::get) }
        screens.forEach { it.start() }
        // The second phones' scans and the guests' first make 50 in a minute from this one IP; `qr.present_ip`
        // allows 60, and k6's scans would go over (step 6c's first run: 7 refused). The next window starts clean.
        Thread.sleep(SCAN_WINDOW.toMillis())
        out.println(
            "[ST-03, ST-10] ${screens.size} screens polling: ${tables.size * 2} phones every ${GUEST_POLL.seconds} s, " +
                "$STAFF_SCREENS staff screens every ${STAFF_POLL.seconds} s; ${screens.count { it.pause != null }} will pause once.",
        )
    }

    /** After the load: a last [TAIL] of polling, a final catch-up on each screen, then the checks. True when both pass. */
    fun finish(wrote: List<Wrote>): Boolean {
        out.println("[ST-03, ST-10] the load is over; the screens poll for ${TAIL.seconds} s more.")
        Thread.sleep(TAIL.toMillis())
        screens.forEach { it.stop() }
        val failed = screens.filter { it.failure != null }
        failed.forEach { out.println("[ST-03, ST-10] ${it.name} stopped polling: ${it.failure?.message}") }
        if (failed.isNotEmpty()) return false
        screens.forEach { it.drain(final = true) }
        File(dir, "screens.txt").writeText(screens.joinToString("") { it.dump() })
        val delays = delays(wrote)
        val gaps = matchDatabase()
        out.println("[ST-03, ST-10] the screens sent ${screens.sumOf { it.requests }} requests.")
        return delays && gaps
    }

    /** Stops every screen, as a run that failed half-way must. */
    fun stop() = screens.forEach { it.stop() }

    private fun delays(wrote: List<Wrote>): Boolean {
        val lag = wrote.map { Duration.between(it.at, it.received).toMillis() }.sorted()
        if (lag.isNotEmpty()) {
            out.println(
                "[ST-03] k6's clock against the driver's: a WROTE line arrived ${lag[lag.size / 2]} ms after its time " +
                    "(median; min ${lag.first()}, max ${lag.last()})",
            )
        }
        return listOf("staff" to STAFF_TARGET, "guest" to GUEST_TARGET)
            .map { (kind, target) -> delays(kind, target, wrote) }
            .all { it }
    }

    /** One kind of screen's delays: every acknowledged write it sees, against the first poll that showed it. */
    private fun delays(
        kind: String,
        target: Target,
        wrote: List<Wrote>,
    ): Boolean {
        val outcomes =
            screens
                .filter { it.kind == kind }
                .flatMap { screen -> wrote.filter { screen.sessionId == null || it.sessionId == screen.sessionId }.map(screen::outcome) }
        val paused = outcomes.count { it.paused }
        val unseen = outcomes.count { !it.paused && it.ms == null }
        val early = outcomes.count { !it.paused && (it.ms ?: 0) < 0 }
        val samples = outcomes.filter { !it.paused }.mapNotNull { it.ms?.coerceAtLeast(0) }.toMutableList()
        samples.sort()
        if (samples.isEmpty()) {
            out.println("[ST-03] $kind screens: no samples")
            return false
        }
        val p95 = samples.at(P95)
        val p99 = samples.at(P99)
        out.println(
            "[ST-03] $kind screens: n ${samples.size}: p50 ${samples.at(P50)} · p95 $p95 · p99 $p99 · max ${samples.last()} ms " +
                "(target p95 ≤ ${target.p95.toMillis()}, p99 ≤ ${target.p99.toMillis()}); " +
                "$paused left out (screen paused), $unseen never shown by a poll, $early shown before k6's acknowledgement time",
        )
        return p95 <= target.p95.toMillis() && p99 <= target.p99.toMillis() && unseen == 0
    }

    /** ST-10: each screen's events against the database's for its view, and no outbox rows (live push off). */
    private fun matchDatabase(): Boolean {
        val target = AppConfig.load(ApplicationConfig("application.yaml")).database.app
        val restaurantId = site.seed.id
        val from = screens.minOf { it.from }
        val upTo = screens.maxOf { it.cursor }
        val rows = events(target, restaurantId, from, upTo)
        var failed = 0
        for (screen in screens) {
            val expected =
                rows
                    .filter { it.seq > screen.from && it.seq <= screen.cursor }
                    .filter { screen.sessionId == null || (it.sessionId == screen.sessionId && it.audience == "staff_and_session") }
                    .map { "${it.seq} ${it.type} ${it.id}" }
            val got = screen.notes.map { "${it.seq} ${it.type} ${it.id}" }
            if (got != expected) {
                failed++
                val missing = (expected - got.toSet()).size
                val extra = (got - expected.toSet()).size
                val repeated = got.size - got.toSet().size
                out.println(
                    "[ST-10] ${screen.name}: ${got.size} events, the database ${expected.size}: " +
                        "$missing missing, $extra extra, $repeated repeated",
                )
            }
        }
        val outbox = outboxRows(target, restaurantId)
        val live = env("LIVE_PUSH")
        out.println(
            "[ST-10] ${screens.size - failed} of ${screens.size} screens hold exactly the database's events " +
                "(seq ${from + 1} to $upTo, ${rows.size} events); outbox rows written since the run started: $outbox (LIVE_PUSH=$live)",
        )
        return failed == 0 && (live != "off" || outbox == 0)
    }

    private class Row(
        val seq: Long,
        val type: String,
        val id: String,
        val sessionId: String?,
        val audience: String,
    )

    private fun events(
        target: JdbcTarget,
        restaurantId: String,
        after: Long,
        upTo: Long,
    ): List<Row> =
        readOnly(target, restaurantId) { conn ->
            conn
                .prepareStatement(
                    "SELECT seq, type, aggregate_id, session_id, audience FROM domain_events " +
                        "WHERE restaurant_id = ?::uuid AND seq > ? ORDER BY seq",
                ).use { st ->
                    st.setString(1, restaurantId)
                    st.setLong(2, after)
                    st.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                val row =
                                    Row(
                                        rs.getLong("seq"),
                                        rs.getString("type"),
                                        rs.getString("aggregate_id"),
                                        rs.getString("session_id"),
                                        rs.getString("audience"),
                                    )
                                if (row.seq <= upTo) add(row)
                            }
                        }
                    }
                }
        }

    private fun outboxRows(
        target: JdbcTarget,
        restaurantId: String,
    ): Int =
        readOnly(target, restaurantId) { conn ->
            conn.prepareStatement("SELECT count(*) FROM outbox WHERE restaurant_id = ?::uuid AND created_at >= ?").use { st ->
                st.setString(1, restaurantId)
                st.setObject(2, started.atOffset(java.time.ZoneOffset.UTC))
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** A read-only transaction as the `app` role, scoped to the restaurant (its row-level security), rolled back. */
    private fun <T> readOnly(
        target: JdbcTarget,
        restaurantId: String,
        block: (java.sql.Connection) -> T,
    ): T =
        DriverManager.getConnection(target.jdbcUrl, target.user, target.password).use { conn ->
            conn.autoCommit = false
            conn.isReadOnly = true
            try {
                conn.prepareStatement("SELECT set_config('app.restaurant_id', ?, true)").use {
                    it.setString(1, restaurantId)
                    it.execute()
                }
                block(conn)
            } finally {
                conn.rollback()
            }
        }

    /** The staff screens' token. A staff access token lasts 15 minutes, less than the screens run, so it's renewed by signing in. */
    private inner class StaffToken {
        private var token: String? = null
        private var at = Instant.EPOCH

        @Synchronized
        fun get(): String {
            if (token == null || Duration.between(at, Instant.now()) > RENEW_AFTER) {
                token = site.signIn(site.seed.manager)
                at = Instant.now()
            }
            return checkNotNull(token)
        }
    }

    private class Seen(
        val seq: Long,
        val type: String,
        val id: String,
        val version: Int?,
        val at: Instant,
        val final: Boolean,
    )

    private inner class Screen(
        val name: String,
        val sessionId: String?,
        private val path: String,
        private val every: Duration,
        private val token: () -> String,
    ) {
        val kind = if (sessionId == null) "staff" else "guest"
        val notes = CopyOnWriteArrayList<Seen>()

        /** When the screen sleeps, from and to; null for a screen that stays open. */
        val pause: Pair<Instant, Instant>? =
            if (Random.nextBoolean()) {
                val from = started.plus(PAUSE_FROM).plusMillis(Random.nextLong(PAUSE_WINDOW.toMillis()))
                from to from.plusMillis(Random.nextLong(PAUSE_MIN.toMillis(), PAUSE_MAX.toMillis()))
            } else {
                null
            }

        var from = 0L
            private set

        @Volatile var cursor = 0L
            private set

        @Volatile var requests = 0
            private set

        /** Why the screen stopped polling before [stop], if it did; the run then fails. */
        @Volatile var failure: Throwable? = null
            private set

        private var thread: Thread? = null

        fun start() {
            from = read(path).long("cursor")
            cursor = from
            thread =
                Thread({ loop() }, name).apply {
                    isDaemon = true
                    start()
                }
        }

        private fun loop() {
            try {
                Thread.sleep(Random.nextLong(every.toMillis()))
                while (true) {
                    val now = Instant.now()
                    if (pause != null && now >= pause.first && now < pause.second) {
                        // Asleep; on waking it catches up at once, as an app does on returning to the foreground.
                        Thread.sleep(Duration.between(now, pause.second).toMillis())
                        continue
                    }
                    drain(final = false)
                    Thread.sleep((every.toMillis() * Random.nextDouble(1 - JITTER, 1 + JITTER)).toLong())
                }
            } catch (_: InterruptedException) {
                // stop(): the screen closes.
            } catch (e: IllegalStateException) {
                failure = e
            } catch (e: java.io.IOException) {
                failure = e
            }
        }

        fun stop() {
            thread?.let {
                it.interrupt()
                it.join(STOP_WAIT_MS)
            }
            thread = null
        }

        /** One catch-up: every page after the cursor until `has_more` is false. */
        @Synchronized
        fun drain(final: Boolean) {
            do {
                val page = read("$path?after=$cursor")
                val at = Instant.now()
                page.getValue("events").jsonArray.forEach { element ->
                    val e = element.jsonObject
                    val aggregate = e.getValue("aggregate").jsonObject
                    notes +=
                        Seen(e.long("seq"), e.str("type"), aggregate.str("id"), aggregate.version(), at, final)
                }
                cursor = page.long("cursor")
            } while (page["has_more"]?.jsonPrimitive?.boolean == true)
        }

        private fun read(path: String): JsonObject {
            requests++
            val first = api.call("GET", path, token = token())
            val response = if (first.status == UNAUTHORIZED && sessionId == null) api.call("GET", path, token = token()) else first
            check(response.status == LoadSite.OK) { "$name: GET ${path.substringBefore('?')} answered ${response.status}" }
            return response.json
        }

        /** How long [w] took to show on this screen's regular polls (the final catch-up doesn't count); null if it never did. */
        fun outcome(w: Wrote): Outcome {
            val seen = firstSeen(w.id, w.version)
            return Outcome(seen?.let { Duration.between(w.at, it).toMillis() }, pausedBetween(w.at, seen ?: Instant.now()))
        }

        /** When this screen's regular polls first showed the order at [version]; the final catch-up doesn't count. */
        fun firstSeen(
            id: String,
            version: Int,
        ): Instant? = notes.firstOrNull { !it.final && it.id == id && it.version == version }?.at

        fun pausedBetween(
            from: Instant,
            to: Instant,
        ): Boolean = pause != null && pause.first < to && pause.second > from

        fun dump(): String =
            "# $name ${sessionId ?: "staff"} from=$from cursor=$cursor pause=${pause?.let { "${it.first}..${it.second}" }}\n" +
                notes.joinToString("") { "${it.seq} ${it.type} ${it.id} ${it.version} ${it.at}${if (it.final) " final" else ""}\n" }
    }

    /** One write on one screen: how long it took to show (null: never), and whether the screen slept meanwhile. */
    private class Outcome(
        val ms: Long?,
        val paused: Boolean,
    )

    private class Target(
        val p95: Duration,
        val p99: Duration,
    )

    companion object {
        private const val GUEST_PATH = "/v1/guest/events"
        private const val STAFF_PATH = "/v1/staff/events"

        /** Test-plan ST-03: five staff screens on L (a kitchen, a counter, three waiters). */
        private const val STAFF_SCREENS = 5

        /** Realtime §7's release-1 timers (CR-013). */
        private val STAFF_POLL: Duration = Duration.ofSeconds(15)
        private val GUEST_POLL: Duration = Duration.ofSeconds(60)
        private const val JITTER = 0.2

        /** NFR-PERF-03's release-1 target (CR-013). */
        private val STAFF_TARGET = Target(Duration.ofSeconds(15), Duration.ofSeconds(20))
        private val GUEST_TARGET = Target(Duration.ofSeconds(60), Duration.ofSeconds(75))

        /** `qr.present_ip`'s window (60 a minute per IP, tables §9). */
        private val SCAN_WINDOW: Duration = Duration.ofSeconds(61)

        /** After the load, longer than a guest's longest timer (72 s), so every write meets a regular poll. */
        private val TAIL: Duration = Duration.ofSeconds(90)

        /** A pause starts 2 to 10 minutes in and lasts 1 to 3 minutes. */
        private val PAUSE_FROM: Duration = Duration.ofMinutes(2)
        private val PAUSE_WINDOW: Duration = Duration.ofMinutes(8)
        private val PAUSE_MIN: Duration = Duration.ofMinutes(1)
        private val PAUSE_MAX: Duration = Duration.ofMinutes(3)

        /** A staff access token lasts 15 minutes (core-and-auth §8). */
        private val RENEW_AFTER: Duration = Duration.ofMinutes(12)
        private const val UNAUTHORIZED = 401
        private const val STOP_WAIT_MS = 10_000L
        private const val P50 = 0.50
        private const val P95 = 0.95
        private const val P99 = 0.99

        /** Requests per screen beyond its polls: the cursor and the final catch-up. */
        private const val EXTRA_PER_SCREEN = 2

        /**
         * Requests the screens add to a run of [minutes] on [tables] tables (test-plan §9's budget guard):
         * their polls over the run and [TAIL], each screen's cursor and final catch-up, the second phones'
         * scans and the staff screens' sign-ins.
         */
        fun requests(
            tables: Int,
            minutes: Int,
        ): Int {
            val seconds = minutes * SECONDS + TAIL.seconds
            val polls = tables * 2 * seconds / GUEST_POLL.seconds + STAFF_SCREENS * seconds / STAFF_POLL.seconds
            return (polls + (tables * 2 + STAFF_SCREENS) * EXTRA_PER_SCREEN + tables + RENEWALS).toInt()
        }

        private const val SECONDS = 60L
        private const val RENEWALS = 3
    }
}

private fun List<Long>.at(p: Double): Long = this[((size * p).toInt()).coerceAtMost(size - 1)]

private fun JsonObject.str(field: String): String = getValue(field).jsonPrimitive.content

private fun JsonObject.long(field: String): Long = str(field).toLong()

private fun JsonObject.version(): Int? =
    this["version"]
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.content
        ?.toInt()
