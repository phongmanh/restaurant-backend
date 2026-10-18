package io.github.phongmanh.restaurant.staging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.exitProcess

/**
 * Test-plan §9 step 6b: the scaled load runs on a staging deployment, one profile a run:
 *
 *     ./gradlew --no-daemon loadTest --args="<URL> --profile smoke [--minutes N] [--rate N] [--force] [--plan-only]"
 *
 * with the environment exported from both env files, as for the system tests (deploy/README.md).
 *
 * - `smoke` (ST-01, ST-02): §3's smoke load, architecture §9's mix at 10 requests a second for 10 minutes.
 * - `cold` (ST-01, ST-02): §3's cold burst. After the setup it waits 30 minutes without a request,
 *   then signs in again (timed: the first request on a cold service) and ramps 0 → 10 a second in a
 *   minute, then 9 minutes at 10.
 * - `deploy` (ST-09, ST-19): the smoke load for 12 minutes, while the owner deploys and then rolls
 *   back. [DeployWatch] reads which deployment the URL's domain serves and prints each switch; give
 *   it the project's domain (`restaurant-staging-liam-36c7.vercel.app`), which follows production.
 * - `live` (ST-03, ST-10; step 6c, CR-013): the smoke load for 14 minutes while 55 screens on L poll
 *   the event log ([LiveScreens]). k6 runs at 9 a second, so with the screens' 1.2 the service gets
 *   the smoke load's 10. 14 minutes, because k6's staff token lasts 15 and can't be renewed inside k6.
 * - `report` (ST-04) and `noisy` (ST-06): [ReportRun] and [NoisyRun].
 *
 * The mix runs in k6's container (`deploy/load/mix.js`) as restaurant L's manager and one guest per
 * table. Afterwards every order k6 saw acknowledged is looked for in the database, and every order in
 * the sessions must be one k6 saw acknowledged: 0 lost, 0 duplicates (ST-09). Then the sessions close.
 *
 * Secrets stay out of files and command lines: the fixtures (tokens) and the bypass secret reach k6
 * as environment variables of the `docker` process. k6 prints order IDs, statuses and request IDs
 * only. The summary goes to `build/load/<run>/summary.json`.
 *
 * The budget guard (test-plan §9): the run prints its planned requests first and refuses above
 * [RUN_LIMIT] unless `--force` is given.
 */
fun main(args: Array<String>) {
    val url = args.firstOrNull()
    val profile = args.optionValue("--profile")?.let { name -> LoadProfile.entries.firstOrNull { it.label == name } }
    if (url == null || !url.startsWith("https://") || profile == null) {
        val names = LoadProfile.entries.joinToString("|") { it.label }
        println("usage: loadTest --args=\"<URL> --profile $names [--minutes N] [--rate N] [--force] [--plan-only]\"")
        exitProcess(2)
    }
    val force = "--force" in args
    val planOnly = "--plan-only" in args
    val code =
        runCatching {
            when (profile) {
                LoadProfile.REPORT -> {
                    ReportRun(url, System.out).run(planOnly)
                }

                LoadProfile.NOISY -> {
                    NoisyRun(url, System.out).run(planOnly)
                }

                else -> {
                    val minutes = args.optionValue("--minutes")?.toInt() ?: profile.minutes
                    val rate = args.optionValue("--rate")?.toInt() ?: profile.rate
                    MixRun(url, profile, rate, minutes, System.out).run(force, planOnly)
                }
            }
        }.getOrElse {
            println("Load run stopped: ${it.message}")
            1
        }
    exitProcess(code)
}

private fun Array<String>.optionValue(name: String): String? = indexOf(name).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

/** Test-plan §3's free profiles: 10 requests a second. */
const val SMOKE_RATE = 10

/** Restaurant L's time zone, as `generateHistory` onboarded it. */
val LOAD_ZONE: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")

/** The accounts the seed and `generateHistory` made (test-plan §6). */
fun loadAccounts(): Accounts = Accounts.read(File(Accounts.PATH))

/** Restaurant L, the largest (test-plan §6). */
fun Accounts.large(): SeededRestaurant =
    checkNotNull(history?.large) { "restaurant L isn't in ${Accounts.PATH}: run generateHistory first" }

/** A run's name, apart from earlier runs': the time in base 36. */
fun runName(): String = java.lang.Long.toString(Instant.now().epochSecond, Character.MAX_RADIX)

enum class LoadProfile(
    val label: String,
    val minutes: Int = 0,
    val idleMinutes: Long = 0,
    val rate: Int = SMOKE_RATE,
) {
    SMOKE("smoke", minutes = 10),
    COLD("cold", minutes = 10, idleMinutes = 30),
    DEPLOY("deploy", minutes = 12),
    LIVE("live", minutes = 14, rate = SMOKE_RATE - 1),
    REPORT("report"),
    NOISY("noisy"),
    ;

    /** k6's requests: the arrival rate over the run (the cold burst ramps for its first minute), plus order replays. */
    fun requests(
        rate: Int,
        minutes: Int,
    ): Int {
        val seconds = minutes * SECONDS
        val iterations = if (this == COLD) rate * SECONDS / 2 + rate * (seconds - SECONDS) else rate * seconds
        return iterations + iterations / REPLAY_SHARE
    }

    private companion object {
        const val SECONDS = 60

        /** mix.js sends one order in five twice, and orders are 3% of the mix: under 1% more. */
        const val REPLAY_SHARE = 100
    }
}

/** One k6 profile's run: setup, the mix, the order check, the clean-up. */
class MixRun(
    url: String,
    private val profile: LoadProfile,
    private val rate: Int,
    private val minutes: Int,
    private val out: PrintStream,
) {
    private val base = url.trimEnd('/')
    private val run = runName()
    private val dir = File("build/load/$run")

    fun run(
        force: Boolean,
        planOnly: Boolean,
    ): Int {
        val large = loadAccounts().large()
        val driver = DRIVER_FIXED + large.tableIds.size * DRIVER_PER_TABLE
        val allowed = plan(large, driver, force)
        if (!allowed || planOnly) return if (allowed) 0 else 1
        val api = StagingApi(base, env("VERCEL_AUTOMATION_BYPASS_SECRET"), driver, out)
        dir.mkdirs()
        val site = LoadSite(api, large)
        var staff = site.signIn(large.manager)
        val left = site.closeOpenSessions(staff)
        if (left > 0) out.println("Closed $left sessions an earlier run left open.")
        val guests = site.freeTables(staff).map { site.scan(site.qrToken(it.text("id"), staff)) }
        // The screens count their requests apart, so they can't spend the budget the clean-up needs.
        val screens =
            if (profile == LoadProfile.LIVE) {
                val budget = LiveScreens.requests(large.tableIds.size, minutes) * 2
                val screensApi = StagingApi(base, env("VERCEL_AUTOMATION_BYPASS_SECRET"), budget, out)
                LiveScreens(screensApi, LoadSite(screensApi, large), out, dir)
            } else {
                null
            }
        try {
            val items = site.orderableItems(guests.first().token, ITEMS)
            check(items.isNotEmpty()) { "${large.name} has no item a guest can order without options" }
            if (profile.idleMinutes > 0) staff = afterIdle(site, large)
            val sessions = guests.map { it.sessionId }.distinct()
            File(dir, "sessions.txt").writeText(sessions.joinToString("\n", postfix = "\n"))
            if (screens != null) {
                screens.start(guests)
                // k6's staff token must last its whole run: 15 minutes from now.
                staff = site.signIn(large.manager)
            }
            val result = k6(fixtures(large.id, staff, items, guests))
            File(dir, "acked.txt").writeText(result.acked.joinToString("\n", postfix = "\n"))
            val live = screens?.finish(result.wrote) ?: true
            staff = site.signIn(large.manager)
            return maxOf(check(result, sessions.flatMap { site.orderIds(it, staff) }.toSet()), if (live) 0 else 1)
        } finally {
            screens?.stop()
            guests.map { it.sessionId }.distinct().forEach { site.close(it, staff) }
            out.println("The driver sent ${api.sent} requests; ${guests.size} sessions closed.")
        }
    }

    /** Prints the run's plan (test-plan §9's budget guard); false when it's over [RUN_LIMIT] without `force`. */
    private fun plan(
        large: SeededRestaurant,
        driver: Int,
        force: Boolean,
    ): Boolean {
        val screens = if (profile == LoadProfile.LIVE) LiveScreens.requests(large.tableIds.size, minutes) else 0
        val planned = profile.requests(rate, minutes) + driver + screens
        out.println(
            "Load run $run (${profile.label}) on $base: $rate requests a second for $minutes minutes, " +
                "at ${large.tableIds.size} tables of ${large.name}; about $planned requests (test-plan §9 allows $RUN_LIMIT a run).",
        )
        if (profile.idleMinutes > 0) out.println("It waits ${profile.idleMinutes} minutes without a request after the setup.")
        if (screens > 0) out.println("Screens on ${large.name} poll the event log meanwhile: about $screens of those requests.")
        val allowed = planned <= RUN_LIMIT || force
        if (!allowed) out.println("Refused: above $RUN_LIMIT requests. Add --force to run it anyway.")
        return allowed
    }

    /** The cold burst's wait, then a timed sign-in: the first request the idle service gets. */
    private fun afterIdle(
        site: LoadSite,
        large: SeededRestaurant,
    ): String {
        val until = Instant.now().plus(Duration.ofMinutes(profile.idleMinutes))
        out.println("Idle until $until (§3's cold burst: 30 minutes without a request).")
        Thread.sleep(Duration.between(Instant.now(), until).toMillis())
        val started = System.nanoTime()
        val token = site.signIn(large.manager)
        val ms = (System.nanoTime() - started) / NANOS_PER_MS
        out.println("[ST-01] the first request after ${profile.idleMinutes} min idle (sign-in): $ms ms")
        return token
    }

    private fun fixtures(
        restaurantId: String,
        staff: String,
        items: List<String>,
        guests: List<LoadGuest>,
    ): String {
        val to = LocalDate.now(LOAD_ZONE).minusDays(1)
        return buildJsonObject {
            put("base", base)
            put("restaurant_id", restaurantId)
            put("staff_token", staff)
            put("report_from", to.minusDays(REPORT_DAYS - 1).toString())
            put("report_to", to.toString())
            putJsonArray("item_ids") { items.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            putJsonArray("guests") {
                guests.forEach { guest ->
                    add(
                        buildJsonObject {
                            put("token", guest.token)
                            put("session_id", guest.sessionId)
                            put("qr_token", guest.qrToken)
                        },
                    )
                }
            }
        }.toString()
    }

    /** What k6 reported: the orders it saw acknowledged, the unexpected answers, the slow requests and its summary. */
    private class K6Result(
        val acked: Set<String>,
        val wrote: List<Wrote>,
        val fails: List<String>,
        val slow: List<Instant>,
        val summary: JsonObject?,
        val started: Instant,
    )

    private fun k6(fixtures: String): K6Result {
        val watch = if (profile == LoadProfile.DEPLOY) DeployWatch(URI(base).host, out).also { it.start() } else null
        val command =
            listOf("docker", "run", "--rm") +
                K6_ENV.flatMap { listOf("-e", it) } +
                listOf("-v", "${dir.absolutePath}:/out:rw", "-v", "${File("deploy/load").absolutePath}:/scripts:ro", IMAGE) +
                listOf("run", "--quiet", "--include-system-env-vars", "--log-format=raw", "/scripts/mix.js")
        val values =
            listOf(fixtures, env("VERCEL_AUTOMATION_BYPASS_SECRET"), profile.label, rate.toString(), minutes.toString(), env("LIVE_PUSH"))
        val acked = mutableSetOf<String>()
        val wrote = mutableListOf<Wrote>()
        val fails = mutableListOf<String>()
        val slow = mutableListOf<Instant>()
        var summary: JsonObject? = null
        val started = Instant.now()
        out.println("k6 started at $started.")
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply { K6_ENV.zip(values).forEach { (name, value) -> environment()[name] = value } }
                .start()
        process.inputStream.bufferedReader().forEachLine { line ->
            when (line.substringBefore(' ')) {
                "ACK" -> {
                    acked += line.substringAfter(' ')
                }

                "WROTE" -> {
                    // WROTE <epoch ms> <session id> <order id> <version>
                    val fields = line.split(' ').drop(1)
                    val (ms, session, id) = fields
                    wrote += Wrote(Instant.ofEpochMilli(ms.toLong()), session, id, fields.last().toInt(), Instant.now())
                }

                "FAIL" -> {
                    fails += line.also { out.println("   $it") }
                }

                "SLOW" -> {
                    slow += Instant.parse(line.split(' ')[1])
                }

                "SUMMARY" -> {
                    summary = Json.parseToJsonElement(line.substringAfter(' ')).jsonObject
                }

                else -> {
                    out.println("   $line")
                }
            }
        }
        val exit = process.waitFor()
        watch?.stop()
        // 99: a threshold wasn't met, which the result reports; anything else means k6 didn't finish.
        check(exit == 0 || exit == K6_THRESHOLDS_FAILED) { "k6's container exited with $exit" }
        return K6Result(acked, wrote, fails, slow, summary, started)
    }

    private fun check(
        result: K6Result,
        inDatabase: Set<String>,
    ): Int {
        val lost = result.acked - inDatabase
        val unacknowledged = inDatabase - result.acked
        out.println("[${profile.tests}] k6 summary: ${result.summary}")
        out.println(
            "[${profile.tests}] orders: ${result.acked.size} acknowledged, ${inDatabase.size} in the sessions; " +
                "${lost.size} lost, ${unacknowledged.size} without an acknowledgement (a duplicate or a lost answer).",
        )
        out.println("[${profile.tests}] unexpected answers: ${result.fails.size}; requests over 2 s by minute: ${slowByMinute(result)}")
        val replays =
            result.summary
                ?.get("replay_mismatch")
                ?.jsonPrimitive
                ?.content ?: "?"
        out.println("[${profile.tests}] replays that answered another order: $replays")
        return if (lost.isEmpty() && unacknowledged.isEmpty() && replays == "0") 0 else 1
    }

    private fun slowByMinute(result: K6Result): String =
        result.slow
            .groupingBy { Duration.between(result.started, it).toMinutes() + 1 }
            .eachCount()
            .toSortedMap()
            .entries
            .joinToString(", ") { (minute, count) -> "min $minute: $count" }
            .ifEmpty { "none" }

    private val LoadProfile.tests: String
        get() =
            when (this) {
                LoadProfile.DEPLOY -> "ST-09, ST-19"
                LoadProfile.LIVE -> "ST-03, ST-10"
                else -> "ST-01, ST-02"
            }

    private companion object {
        /** Pinned, like ZAP's image; 1.3.0 is k6's release of 2025-09. */
        const val IMAGE = "grafana/k6:1.3.0"
        val K6_ENV = listOf("LOAD_FIXTURES", "BYPASS", "PROFILE", "RATE", "MINUTES", "LIVE_PUSH")
        const val K6_THRESHOLDS_FAILED = 99

        /** Three sign-ins, two table lists, the menu, and room for a sign-in's retries. */
        const val DRIVER_FIXED = 12

        /** Per table: the QR code, the scan, the order list, and the close's three calls; and one more close for a left-over session. */
        const val DRIVER_PER_TABLE = 9
        const val ITEMS = 50
        const val REPORT_DAYS = 31L
        const val NANOS_PER_MS = 1_000_000
    }
}

/**
 * ST-09, ST-19: which deployment [domain] serves, read with `vercel inspect` every [POLL], with each
 * switch printed at its time. After a switch the production domain (deploy/README.md step 10) is
 * checked without the bypass header; a promotion can give it back to the new deployment, and if it
 * answers anything but `404` its alias is removed at once, as step 10 does.
 *
 * Vercel's rollback doesn't move [domain]: it moves the production domain, which staging removes, and
 * on 2026-10-18 the CLI's rollback answered `500` twice and the dashboard's changed nothing. So ST-19
 * switches back with `vercel alias set`, the same instant switch on the domain under test.
 */
class DeployWatch(
    private val domain: String,
    private val out: PrintStream,
) {
    val switches = CopyOnWriteArrayList<Pair<Instant, String>>()

    @Volatile private var running = true
    private val thread = Thread(::watch, "deploy-watch").apply { isDaemon = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_S)).build()

    fun start() {
        val first = checkNotNull(current()) { "vercel inspect $domain didn't name a deployment" }
        switches += Instant.now() to first
        out.println("[ST-09, ST-19] $domain serves $first.")
        out.println("To switch it: vercel deploy --prod; to switch back: vercel alias set <deployment> $domain")
        thread.start()
    }

    fun stop() {
        running = false
        thread.interrupt()
        out.println("[ST-09, ST-19] deployments served: ${switches.joinToString(" → ") { (at, url) -> "$url from $at" }}")
    }

    private fun watch() {
        while (running) {
            try {
                Thread.sleep(POLL.toMillis())
            } catch (_: InterruptedException) {
                return
            }
            val now = current() ?: continue
            if (now != switches.last().second) {
                switches += Instant.now() to now
                out.println("[ST-09, ST-19] SWITCH at ${Instant.now()}: $domain serves $now")
                closePublicDomain()
            }
        }
    }

    private fun current(): String? {
        val process = ProcessBuilder("vercel", "inspect", domain).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return FETCHED.find(text)?.groupValues?.get(1)
    }

    private fun closePublicDomain() {
        val request = HttpRequest.newBuilder(URI("https://$PUBLIC_DOMAIN/health")).timeout(Duration.ofSeconds(TIMEOUT_S)).build()
        val status = runCatching { http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() }.getOrNull()
        if (status == NOT_FOUND) return
        out.println("[deploy/README.md step 10] $PUBLIC_DOMAIN answered $status without the bypass header: removing its alias")
        val removed = ProcessBuilder("vercel", "alias", "rm", PUBLIC_DOMAIN, "--yes").redirectErrorStream(true).start().waitFor()
        out.println("[deploy/README.md step 10] vercel alias rm exited with $removed")
    }

    private companion object {
        val POLL: Duration = Duration.ofSeconds(10)
        const val TIMEOUT_S = 20L
        const val NOT_FOUND = 404
        const val PUBLIC_DOMAIN = "restaurant-staging-ecru.vercel.app"
        val FETCHED = Regex("""Fetched deployment "([^"]+)"""")
    }
}

private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content
