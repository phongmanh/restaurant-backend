package io.github.phongmanh.restaurant.staging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.PrintStream
import java.net.URI
import java.time.Instant
import kotlin.system.exitProcess

/**
 * ST-11 (NFR-SEC-01): ZAP's API scan of a staging deployment, driven by the contract, in two runs:
 *
 *     ./gradlew --no-daemon zapScan --args="<deployment URL> --profiles guest,staff,floor"
 *     ./gradlew --no-daemon zapScan --args="<deployment URL> --profiles admin,anonymous"
 *
 * with the environment exported from both env files, as for the system tests (deploy/README.md).
 * `--plan-only` writes each profile's contract and plan without sending anything.
 *
 * Each run first onboards a restaurant of its own, Z ([ZapSite]), so the scan's writes land there
 * and not in A or B, and its IDs become the contract's path examples ([ZapPlan]). Then each
 * [ZapProfile] scans its part of the contract with its own token, in ZAP's container.
 *
 * Secrets stay out of files: the bypass secret reaches ZAP as `ZAP_AUTH_HEADER_VALUE` and the
 * profile's token as `ZAP_TOKEN`, both environment variables of the `docker` process, expanded only
 * inside the container. The reports go to `build/zap/<run>/`, which git ignores; they can quote
 * response bodies, so they stay there.
 *
 * The budget guard (test-plan §9): `deploy/zap/budget.js` counts every request ZAP sends and stops
 * the scan at the profile's cap. The run prints its caps before it starts and refuses above
 * [RUN_LIMIT], which all five profiles together are: hence the two runs.
 */
fun main(args: Array<String>) {
    val url = args.firstOrNull()
    if (url == null || !url.startsWith("https://")) {
        println("usage: zapScan --args=\"<deployment URL> --profiles guest,staff,floor|admin,anonymous [--force] [--plan-only]\"")
        exitProcess(2)
    }
    val profiles =
        args
            .optionValue("--profiles")
            ?.split(',')
            ?.map { name -> ZapProfile.valueOf(name.trim().uppercase()) }
            ?: ZapProfile.entries
    val scan = ZapScan(url, profiles.sortedBy { it.ordinal }, System.out)
    val code =
        runCatching { scan.run(force = "--force" in args, planOnly = "--plan-only" in args) }.getOrElse {
            println("ZAP scan stopped: ${it.message}")
            1
        }
    exitProcess(code)
}

private fun Array<String>.optionValue(name: String): String? = indexOf(name).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

/** test-plan §9: 10,000 requests a run. */
const val RUN_LIMIT = 10_000

class ZapScan(
    url: String,
    private val profiles: List<ZapProfile>,
    private val out: PrintStream,
) {
    private val base = url.trimEnd('/')
    private val run: String = java.lang.Long.toString(Instant.now().epochSecond, Character.MAX_RADIX)
    private val dir = File("build/zap/$run")
    private val plans = ZapPlan(base, run)

    fun run(
        force: Boolean,
        planOnly: Boolean,
    ): Int {
        plans.checkStaffSplit()
        val budget = ZapSite.SETUP + (if (ZapProfile.ADMIN in profiles) ZapSite.CLEANUP else 0)
        val planned = budget + profiles.sumOf { it.cap }
        val list = profiles.joinToString { "${it.label} (${it.minutes} min, at most ${it.cap})" }
        out.println("ZAP scan of $base, run $run: $list; at most $planned requests with the setup (test-plan §9 allows $RUN_LIMIT a run).")
        val refused = planned > RUN_LIMIT && !force
        if (refused) {
            out.println("Refusing: above the run's budget. Scan in two runs (guest,staff,floor, then admin,anonymous), or pass --force.")
        } else if (planOnly) {
            profiles.forEach { plans.write(profileDir(it), it, ZapPlan.PATH_EXAMPLES.values.associateWith { ZapPlan.PLACEHOLDER }) }
            out.println("Plans written to $dir; nothing sent.")
        } else {
            scanAll(budget)
        }
        return if (refused) {
            1
        } else if (planOnly) {
            0
        } else {
            summarise()
        }
    }

    private fun scanAll(budget: Int) {
        val started = Instant.now()
        val api = StagingApi(base, env("VERCEL_AUTOMATION_BYPASS_SECRET"), budget, out)
        val site = ZapSite(api, run, out)
        val z = site.setUp()
        val sent =
            profiles.associateWith { profile ->
                plans.write(profileDir(profile), profile, z.ids)
                scan(profile, z.token(profile)).also {
                    if (profile ==
                        ZapProfile.ADMIN
                    ) {
                        site.cleanUp(requireNotNull(z.token(profile)), started)
                    }
                }
            }
        val each = sent.entries.joinToString { "${it.key.label} ${it.value}" }
        out.println("ZAP scan sent ${api.sent + sent.values.sum()} requests: setup and clean-up ${api.sent}, $each.")
    }

    /** Runs the profile's plan in ZAP's container; returns the requests ZAP sent. */
    private fun scan(
        profile: ZapProfile,
        token: String?,
    ): Int {
        out.println("== ${profile.label}: at most ${profile.minutes} min and ${profile.cap} requests")
        // The token's header, expanded from ZAP_TOKEN by the container's shell, never on this command line.
        val replacer =
            if (token ==
                null
            ) {
                ""
            } else {
                AUTH_RULE.joinToString(" ") { (key, value) -> "-config \"replacer.full_list(0).$key=$value\"" }
            }
        val command =
            listOf("docker", "run", "--rm") +
                ZAP_ENV.flatMap { listOf("-e", it) } +
                listOf("-v", "${profileDir(profile).absolutePath}:/zap/wrk:rw", IMAGE) +
                listOf("sh", "-c", "exec zap.sh -cmd -silent $replacer -autorun /zap/wrk/plan.yaml")
        val values = listOf("x-vercel-protection-bypass", env("VERCEL_AUTOMATION_BYPASS_SECRET"), URI(base).host, token.orEmpty())
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply { ZAP_ENV.zip(values).forEach { (name, value) -> environment()[name] = value } }
                .start()
        var sent: Int? = null
        process.inputStream.bufferedReader().forEachLine { line ->
            if (SHOWN.any { line.contains(it) }) out.println("   $line")
            line.substringAfter("[budget] sent ", "").toIntOrNull()?.let { sent = it }
        }
        check(process.waitFor() == 0) { "ZAP's container exited with ${process.exitValue()} for ${profile.label}" }
        return checkNotNull(sent) { "ZAP didn't report what it sent for ${profile.label}" }
    }

    private class Finding(
        val risk: Int,
        val rule: String,
        val name: String,
    ) {
        val profiles = sortedSetOf<String>()
        var instances = 0
        var example = ""
    }

    /** Every profile's alerts in one table, `build/zap/<run>/summary.md`; 1 when any is High. */
    private fun summarise(): Int {
        val findings = linkedMapOf<Pair<String, String>, Finding>()
        profiles.forEach { profile ->
            alerts(File(profileDir(profile), "zap-${profile.label}.json")).forEach { alert ->
                val rule = alert.text("pluginid")
                val finding =
                    findings.getOrPut(
                        rule to alert.text("name"),
                    ) { Finding(alert.text("riskcode").toInt(), rule, alert.text("name")) }
                val instances = alert["instances"]?.jsonArray.orEmpty().map { it.jsonObject }
                finding.profiles += profile.label
                finding.instances += instances.size
                finding.example =
                    finding.example.ifEmpty {
                        instances
                            .firstOrNull()
                            ?.let {
                                "${it.text(
                                    "method",
                                )} ${URI(it.text("uri")).path}"
                            }.orEmpty()
                    }
            }
        }
        val rows = findings.values.sortedWith(compareByDescending<Finding> { it.risk }.thenBy { it.rule.toInt() })
        val table =
            buildString {
                appendLine("| Risk | Rule | Alert | Profiles | Instances | Example |")
                appendLine("|---|---|---|---|---|---|")
                rows.forEach {
                    val cells = listOf(RISKS[it.risk], it.rule, it.name, it.profiles.joinToString(), "${it.instances}", "`${it.example}`")
                    appendLine(cells.joinToString(" | ", "| ", " |"))
                }
            }
        File(dir, "summary.md").writeText("# ZAP scan, run $run\n\n$table")
        out.println(table)
        val high = rows.count { it.risk == HIGH }
        out.println("${rows.size} alerts, $high High. Reports: ${dir.path}/<profile>/zap-<profile>.html")
        return if (high == 0) 0 else 1
    }

    private fun alerts(report: File): List<JsonObject> =
        Json
            .parseToJsonElement(report.readText())
            .jsonObject["site"]
            ?.jsonArray
            .orEmpty()
            .flatMap { it.jsonObject["alerts"]?.jsonArray.orEmpty() }
            .map { it.jsonObject }

    private fun profileDir(profile: ZapProfile) = File(dir, profile.label)

    private companion object {
        const val IMAGE = "ghcr.io/zaproxy/zaproxy:2.17.0"
        const val HIGH = 3
        val RISKS = listOf("Informational", "Low", "Medium", "High")

        /** ZAP's own variables for one extra header on every request to the site, and the token for the replacer rule. */
        val ZAP_ENV = listOf("ZAP_AUTH_HEADER", "ZAP_AUTH_HEADER_VALUE", "ZAP_AUTH_HEADER_SITE", "ZAP_TOKEN")

        val AUTH_RULE =
            listOf(
                "description" to "token",
                "enabled" to "true",
                "matchtype" to "REQ_HEADER",
                "matchstr" to "Authorization",
                "regex" to "false",
                "replacement" to "Bearer \$ZAP_TOKEN",
            )

        /** ZAP's output lines worth showing: progress, the budget and anything that went wrong. */
        val SHOWN =
            listOf(
                "[budget]",
                "[rule]",
                "openapi added",
                "activeScan started",
                "activeScan finished",
                "Automation plan",
                "Failure",
                "ERROR",
            )
    }
}

private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content
