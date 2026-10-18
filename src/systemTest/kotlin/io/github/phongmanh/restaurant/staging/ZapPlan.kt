package io.github.phongmanh.restaurant.staging

import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle
import java.io.File

/**
 * What each profile of ST-11's scan covers: the paths it imports from the contract (an entry
 * ending in `/` takes every path under it), the ones its active scan attacks, its request cap and
 * its time limit. They run in this order, because the later ones end what the earlier ones use (a
 * close on the floor ends the guest's session, an admin suspends Z), and the anonymous one spends
 * the sign-in limits. No profile with a token scans `/v1/auth/sign-out` or `/v1/auth/refresh`,
 * which would end it.
 *
 * A profile with a token finishes within [TOKEN_MINUTES], before its 15-minute access token ends
 * (architecture §7), so the staff paths are split in two, each with a fresh token. The anonymous
 * profile imports every path, so each operation is called once without a token, but attacks only
 * those that take none: elsewhere the `401` comes before the input is read (core-and-auth §3), so
 * an attack there tests nothing. The caps come from a run against a stub that answers everything,
 * plus about 8 %.
 */
enum class ZapProfile(
    val paths: List<String>,
    val cap: Int,
    val minutes: Int,
    val attacked: List<String> = paths,
) {
    GUEST(listOf("/v1/guest/"), cap = 1_200, minutes = 6),
    STAFF(
        listOf("/v1/staff/restaurant", "/v1/staff/members", "/v1/staff/members/", "/v1/staff/audit-events") +
            listOf("/v1/staff/menu", "/v1/staff/menu/", "/v1/auth/me", "/v1/auth/password"),
        cap = 4_300,
        minutes = TOKEN_MINUTES,
    ),
    FLOOR(
        listOf("/v1/staff/tables", "/v1/staff/tables/", "/v1/staff/table-sessions/", "/v1/staff/waiter-calls") +
            listOf("/v1/staff/waiter-calls/", "/v1/staff/orders", "/v1/staff/orders/", "/v1/staff/bills") +
            listOf("/v1/staff/events", "/v1/staff/realtime-token", "/v1/staff/reports/"),
        cap = 4_300,
        minutes = TOKEN_MINUTES,
    ),
    ADMIN(listOf("/v1/admin/", "/v1/auth/me", "/v1/auth/password"), cap = 4_500, minutes = TOKEN_MINUTES),
    ANONYMOUS(
        listOf("/"),
        cap = 1_500,
        minutes = 6,
        attacked =
            listOf("/v1/auth/sign-in", "/v1/auth/mfa", "/v1/auth/refresh", "/v1/auth/sign-out", "/v1/auth/links/inspect") +
                listOf("/v1/auth/invitations/accept", "/v1/auth/password-resets/complete", "/v1/guest/sessions"),
    ),
    ;

    val label: String get() = name.lowercase()

    fun takes(path: String): Boolean = paths.any { if (it.endsWith('/')) path.startsWith(it) else path == it }
}

/** A token profile's scan time: its access token lasts 15 minutes. */
const val TOKEN_MINUTES = 12

/** Writes a profile's folder for ZAP's container: its part of the contract, its plan and the two scripts. */
class ZapPlan(
    private val base: String,
    private val run: String,
) {
    fun write(
        folder: File,
        profile: ZapProfile,
        ids: Map<String, String>,
    ) {
        folder.mkdirs()
        File(folder, "openapi.yaml").writeText(contract(profile, ids))
        File(folder, "plan.yaml").writeText(plan(profile))
        File(folder, "budget.js").writeText(File("deploy/zap/budget.js").readText().replace("__CAP__", profile.cap.toString()))
        File("deploy/zap/sent.js").copyTo(File(folder, "sent.js"), overwrite = true)
    }

    /** Every `/v1/staff` path belongs to exactly one of the two staff profiles, so a new one isn't left unscanned. */
    fun checkStaffSplit() {
        val unsplit =
            paths(load()).keys.filter { path ->
                path.startsWith("/v1/staff/") && listOf(ZapProfile.STAFF, ZapProfile.FLOOR).count { it.takes(path) } != 1
            }
        check(unsplit.isEmpty()) { "each /v1/staff path belongs to exactly one of the staff and floor profiles: $unsplit" }
    }

    /**
     * The profile's paths, the deployment as the server, and the setup's IDs as the path examples,
     * which ZAP uses. ZAP 2.17's import fills path parameters only in an OpenAPI 3.0 document (from
     * a 3.1 one it sends `{tableId}` as it is), so the copy says 3.0.3: the contract uses no 3.1-only
     * schema feature but two `examples` lists, which a 3.0 reader skips. Every parameter given by
     * `$ref` is written out in place, as tried against a stub.
     */
    @Suppress("UNCHECKED_CAST")
    private fun contract(
        profile: ZapProfile,
        ids: Map<String, String>,
    ): String {
        val doc = load()
        doc["openapi"] = "3.0.3"
        doc["servers"] = listOf(mapOf("url" to base))
        paths(doc).keys.retainAll { profile.takes(it) }
        val parameters = (doc.getValue("components") as Map<String, Any?>).getValue("parameters") as Map<String, MutableMap<String, Any?>>
        PATH_EXAMPLES.forEach { (component, name) -> parameters.getValue(component)["example"] = ids.getValue(name) }

        fun inline(list: Any?) =
            (list as List<Map<String, Any?>>?)?.map { parameter ->
                (parameter["\$ref"] as String?)?.let { parameters.getValue(it.substringAfter(PARAMETER_REF)) } ?: parameter
            }
        paths(doc).values.forEach { item ->
            val operations = item as MutableMap<String, Any?>
            operations["parameters"]?.let { operations["parameters"] = inline(it) }
            operations.values.filterIsInstance<MutableMap<String, Any?>>().forEach { operation ->
                operation["parameters"]?.let { operation["parameters"] = inline(it) }
            }
        }
        // Written out in full where a map repeats: ZAP's import doesn't follow YAML aliases either.
        val settings =
            DumpSettings
                .builder()
                .setDefaultFlowStyle(FlowStyle.BLOCK)
                .setDereferenceAliases(true)
                .build()
        return Dump(settings).dumpToString(doc)
    }

    @Suppress("UNCHECKED_CAST")
    private fun load() = Load(LoadSettings.builder().build()).loadFromString(File(CONTRACT).readText()) as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun paths(doc: Map<String, Any?>) = doc.getValue("paths") as MutableMap<String, Any?>

    /**
     * ZAP's automation plan: the budget script, the contract's import (one request per operation),
     * the active scan of the `attacked` context, the reports and the count. A context takes
     * everything under each of its `urls`, so the attacked one lists the attacked paths themselves.
     */
    private fun plan(profile: ZapProfile): String {
        val report = "zap-${profile.label}"
        val attacked = if (profile.attacked == profile.paths) listOf(base) else profile.attacked.map { base + it }
        return """
            |# ST-11, profile ${profile.label}: written by ZapScan for run $run.
            |env:
            |  contexts:
            |    - name: staging
            |      urls: ["$base"]
            |    - name: attacked
            |      urls: [${attacked.joinToString { "\"$it\"" }}]
            |  parameters: {failOnError: false, failOnWarning: false, progressToStdout: true}
            |jobs:
            |  - type: script
            |    parameters: {action: add, type: httpsender, engine: "$ENGINE", name: budget, file: /zap/wrk/budget.js}
            |  - type: openapi
            |    parameters: {apiFile: /zap/wrk/openapi.yaml, targetUrl: "$base", context: staging}
            |  - type: passiveScan-wait
            |    parameters: {maxDuration: 2}
            |  - type: activeScan
            |    parameters:
            |      context: attacked
            |      maxScanDurationInMins: ${profile.minutes}
            |      threadPerHost: $THREADS
            |      delayInMs: $DELAY_MS
            |      addQueryParam: false
            |      scanHeadersAllRequests: false
            |      injectPluginIdInHeader: false
            |    policyDefinition:
            |      defaultStrength: low
            |      defaultThreshold: "off"
            |      rules:
            |${ACTIVE_RULES.joinToString("\n") { "        - {id: $it, strength: low, threshold: medium}" }}
            |  - type: passiveScan-wait
            |    parameters: {maxDuration: 2}
            |  - type: report
            |    parameters: {template: traditional-json, reportDir: /zap/wrk, reportFile: $report}
            |  - type: report
            |    parameters: {template: traditional-html, reportDir: /zap/wrk, reportFile: $report}
            |  - type: script
            |    parameters: {action: add, type: standalone, engine: "$ENGINE", name: sent, file: /zap/wrk/sent.js}
            |  - type: script
            |    parameters: {action: run, type: standalone, name: sent}
            |
            """.trimMargin()
    }

    companion object {
        const val PLACEHOLDER = "00000000-0000-7000-8000-000000000000"
        private const val CONTRACT = "docs/api/openapi.yaml"
        private const val PARAMETER_REF = "#/components/parameters/"
        private const val ENGINE = "ECMAScript : Graal.js"

        /** Two threads, each pausing 100 ms after a request: at most 20 a second, about 8 from Vietnam to `sin1`. */
        private const val THREADS = 2
        private const val DELAY_MS = 100

        /** The contract's shared path parameters and the setup's ID each one gets as its example. */
        val PATH_EXAMPLES =
            mapOf(
                "RestaurantId" to "restaurantId",
                "StaffId" to "staffId",
                "CategoryId" to "categoryId",
                "ItemId" to "itemId",
                "TableId" to "tableId",
                "SessionId" to "sessionId",
                "OrderId" to "orderId",
                "LineId" to "lineId",
                "CallId" to "callId",
            )

        /**
         * The active rules that fit a JSON API on the JVM and PostgreSQL, at low strength: injection
         * (SQL, PostgreSQL time-based, OS command, server-side code, templates, CRLF), path
         * traversal, parameter tampering, cloud metadata and leaked files. Every passive rule runs.
         * Left out, because this service gives them nothing to find and the budget is tight (the
         * whole set would cost about 16,500 requests): reflected XSS (40012; every body is JSON with
         * `nosniff`), C format strings (30002; the JVM has none, and no format string comes from
         * input), external redirects (20019; the service sends no redirects), browser-only rules,
         * other databases' and XML's. ZAP 2.17.0's stable image doesn't have 40040 (CORS), 90025
         * (expression language) or 90028 (HTTP methods).
         */
        private val ACTIVE_RULES = listOf(6, 40003, 40008, 40018, 40022, 40034, 40035, 90019, 90020, 90034, 90035)
    }
}
