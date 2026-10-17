package io.github.phongmanh.restaurant.core.observability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

/** The alert rules and dashboards of `deploy/observability` (Phase 5 step 4c), as the tests read them. */
object AlertRules {
    val dir = File(System.getProperty("observability.path") ?: "deploy/observability")

    /** Every rule of `rules.yaml`, as parsed. */
    @Suppress("UNCHECKED_CAST")
    val rules: List<Map<String, Any?>> by lazy {
        val doc = File(dir, "rules.yaml").inputStream().use { Load(LoadSettings.builder().build()).loadFromInputStream(it) }
        ((doc as Map<String, Any?>)["groups"] as List<Map<String, Any?>>).flatMap { it["rules"] as List<Map<String, Any?>> }
    }

    val alerts get() = rules.filter { "alert" in it }

    /** The recording rules' names, e.g. `slo:availability:error_ratio_rate5m`. */
    val recorded: Set<String> get() = rules.mapNotNull { it["record"] as String? }.toSet()

    /** Each dashboard file and every query in it. */
    val dashboards: Map<String, List<String>> by lazy {
        File(dir, "dashboards").listFiles { f -> f.extension == "json" }!!.associate { f ->
            f.name to exprs(Json.parseToJsonElement(f.readText()))
        }
    }

    /** Every query: the rules' and the dashboards', with where each comes from. */
    val queries: List<Pair<String, String>> by lazy {
        rules.map { (it["record"] ?: it["alert"]).toString() to it["expr"].toString() } +
            dashboards.flatMap { (file, exprs) -> exprs.map { file to it } }
    }

    /** One series selector in a query: `name{label op "value", …}`. */
    data class Selector(
        val metric: String,
        val matchers: List<Matcher>,
    )

    data class Matcher(
        val label: String,
        val op: String,
        val value: String,
    )

    private val selector = Regex("""([a-zA-Z_:][a-zA-Z0-9_:]*)\{([^}]*)\}""")
    private val matcher = Regex("""(\w+)\s*(=~|!~|!=|=)\s*"((?:[^"\\]|\\.)*)"""")
    private val recordedName = Regex("""\bslo:[a-z0-9_:]+""")

    fun selectors(expr: String): List<Selector> =
        selector
            .findAll(expr)
            .map { m ->
                val matchers = matcher.findAll(m.groupValues[2]).map { Matcher(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
                Selector(m.groupValues[1], matchers.toList())
            }.toList()

    /** The recording rules a query reads, with or without a selector. */
    fun recordedNames(expr: String): Set<String> = recordedName.findAll(expr).map { it.value }.toSet()

    private fun exprs(e: JsonElement): List<String> =
        when (e) {
            is JsonObject -> {
                e.entries.flatMap { (k, v) -> if (k == "expr" && v is JsonPrimitive) listOfNotNull(v.contentOrNull) else exprs(v) }
            }

            is JsonArray -> {
                e.flatMap { exprs(it) }
            }

            else -> {
                emptyList()
            }
        }
}
