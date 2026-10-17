package io.github.phongmanh.restaurant.core.observability

import com.sun.net.httpserver.HttpServer
import io.github.phongmanh.restaurant.core.http.Endpoint
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.service
import io.github.phongmanh.restaurant.support.TestDatabase
import io.ktor.client.request.get
import io.ktor.http.HttpMethod
import io.ktor.server.testing.testApplication
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.metrics.v1.Metric
import org.koin.ktor.ext.get
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The alert rules and dashboards read only what the service pushes (Phase 5 step 4c). A query that
 * names a series nobody sends finds no data, and an alert on no data never fires, silently. So the
 * test starts the service with its OTLP push pointed at a local listener, records every meter once,
 * turns what arrives into Prometheus names the way Grafana Cloud does, and checks every selector in
 * `rules.yaml` and the dashboards against them: the metric, its labels, its `le` buckets and its
 * routes.
 */
class MetricNamesTest {
    /** A pushed series: its labels and, for a histogram's buckets, the bounds. */
    private data class Pushed(
        val labels: Set<String>,
        val bounds: Set<String> = emptySet(),
    )

    @Test
    fun `every series the rules and dashboards read is one the service pushes`() {
        val pushed = push()
        val problems =
            AlertRules.queries.flatMap { (from, expr) ->
                // A series named without a selector reads every service's series, not only this one's.
                val bare = pushed.keys.filter { Regex("""\b$it\b(?!\{)""").containsMatchIn(expr) }.map { "$it without a selector" }
                val unrecorded = AlertRules.recordedNames(expr).filter { it !in AlertRules.recorded }.map { "no rule records $it" }
                val wrong = AlertRules.selectors(expr).filter { !it.metric.startsWith("slo:") }.flatMap { problems(it, pushed[it.metric]) }
                (bare + unrecorded + wrong).map { "$from: $it" }
            }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /** What's wrong with one selector: a metric, label, bucket or route the service doesn't push. */
    private fun problems(
        s: AlertRules.Selector,
        series: Pushed?,
    ): List<String> {
        if (series == null) return listOf("the service pushes no ${s.metric}")
        val job =
            if (s.matchers.none {
                    it ==
                        AlertRules.Matcher(
                            "job",
                            "=",
                            Metrics.SERVICE,
                        )
                }
            ) {
                listOf("${s.metric} without job")
            } else {
                emptyList()
            }
        return job +
            s.matchers.mapNotNull { m ->
                when {
                    m.label !in series.labels + RESOURCE_LABELS -> "${s.metric} has no label ${m.label}"
                    m.label == "le" && m.value !in series.bounds -> "${s.metric} has no bucket le=${m.value}"
                    m.label == "route" && routes(m).isEmpty() -> "route ${m.op}\"${m.value}\" matches no endpoint"
                    else -> null
                }
            }
    }

    @Test
    fun `the SLOs' route patterns cover exactly their requirements' endpoints`() {
        val patterns =
            AlertRules.rules
                .flatMap { AlertRules.selectors(it["expr"].toString()) }
                .flatMap { s -> s.matchers.filter { it.label == "route" }.map { s.matchers.methodOf() to it } }
                .toSet()
        val byMethod = patterns.groupBy({ it.first }, { routes(it.second, it.first) })
        val o = Endpoints.Orders
        val t = Endpoints.Tables
        // NFR-AVAIL-02: order placements, by guests and by staff.
        assertEquals(setOf(setOf(o.PLACE_GUEST_ORDER, o.PLACE_STAFF_ORDER)), byMethod[HttpMethod.Post]?.toSet())
        // NFR-PERF-01: guest reads of the menu, the session, the orders and the bill; not the catch-up.
        val guestReads = setOf(Endpoints.Menu.GET_GUEST_MENU, t.GET_GUEST_SESSION, o.LIST_GUEST_ORDERS, o.GET_GUEST_ORDER, o.GET_GUEST_BILL)
        assertEquals(setOf(guestReads), byMethod[HttpMethod.Get]?.toSet())
    }

    /** The endpoints a `route` matcher selects, as PromQL matches: the whole path. */
    private fun routes(
        m: AlertRules.Matcher,
        method: HttpMethod? = null,
    ): Set<Endpoint> {
        val all = Endpoints.all.filter { method == null || it.method == method }
        return when (m.op) {
            "=" -> all.filter { it.path == m.value }
            "=~" -> Regex(m.value).let { re -> all.filter { re.matches(it.path) } }
            else -> all
        }.toSet()
    }

    private fun List<AlertRules.Matcher>.methodOf(): HttpMethod? = firstOrNull { it.label == "method" }?.let { HttpMethod.parse(it.value) }

    /** Starts the service with its push pointed here, records every meter, and returns what arrived. */
    private fun push(): Map<String, Pushed> {
        val bodies = CopyOnWriteArrayList<ByteArray>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            bodies += exchange.requestBody.readAllBytes()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            // An hour's step: the only push is the one when the application stops.
            val otlp = ObservabilitySettings("http://127.0.0.1:${server.address.port}", emptyMap(), Duration.ofHours(1))
            testApplication {
                application {
                    service(TestDatabase.config.copy(observability = otlp))
                    get<ServiceMeters>().apply {
                        published("inline", "sent")
                        feedDelay(Duration.ofMillis(100))
                        outbox(0, null, Instant.now())
                        orderPlaced(byStaff = false)
                        sessionOpened()
                        reconcile(1, 0, 0, 0, Instant.now())
                        rateLimitFailedOpen("auth.sign_in", "timeout")
                    }
                }
                client.get(Endpoints.Menu.GET_GUEST_MENU.path)
            }
        } finally {
            server.stop(0)
        }
        assertTrue(bodies.isNotEmpty(), "the service pushed when it stopped")
        return bodies
            .flatMap { ExportMetricsServiceRequest.parseFrom(it).resourceMetricsList }
            .flatMap { it.scopeMetricsList }
            .flatMap { it.metricsList }
            .flatMap { prometheusSeries(it).entries }
            .associate { it.key to it.value }
    }

    /**
     * The Prometheus series Grafana Cloud makes of one OTLP metric, by OpenTelemetry's compatibility
     * rules (with suffixes): characters other than letters, digits, `_` and `:` become `_`; the unit
     * is added unless the name already ends with it; a monotonic sum gets `_total`; a histogram
     * becomes `_bucket` (with `le`), `_sum` and `_count`. Micrometer sends units spelled out
     * (`milliseconds`, `bytes`), so only the short forms need mapping.
     */
    private fun prometheusSeries(metric: Metric): Map<String, Pushed> {
        val unit = unitSuffix(metric.unit)
        var name = metric.name.replace(Regex("[^a-zA-Z0-9_:]"), "_")
        if (unit.isNotEmpty() && !name.endsWith("_$unit")) name += "_$unit"
        return when (metric.dataCase) {
            Metric.DataCase.HISTOGRAM -> {
                val points = metric.histogram.dataPointsList
                val labels = points.flatMap { p -> p.attributesList.map { it.key } }.toSet()
                val bounds = points.flatMap { it.explicitBoundsList }.map { BigDecimal(it).stripTrailingZeros().toPlainString() }
                mapOf(
                    "${name}_bucket" to Pushed(labels + "le", bounds.toSet() + "+Inf"),
                    "${name}_sum" to Pushed(labels),
                    "${name}_count" to Pushed(labels),
                )
            }

            Metric.DataCase.SUM -> {
                val labels =
                    metric.sum.dataPointsList
                        .flatMap { p -> p.attributesList.map { it.key } }
                        .toSet()
                val total = if (metric.sum.isMonotonic && !name.endsWith("_total")) "${name}_total" else name
                mapOf(total to Pushed(labels))
            }

            Metric.DataCase.GAUGE -> {
                mapOf(
                    name to
                        Pushed(
                            metric.gauge.dataPointsList
                                .flatMap { p -> p.attributesList.map { it.key } }
                                .toSet(),
                        ),
                )
            }

            else -> {
                emptyMap()
            }
        }
    }

    /** The name suffix for an OTLP unit: UCUM's short forms spelled out; none for `1` or `{…}`. */
    private fun unitSuffix(unit: String): String =
        when {
            unit == "" || unit == "1" || unit.startsWith("{") -> ""
            unit == "ms" -> "milliseconds"
            unit == "s" -> "seconds"
            unit == "ns" -> "nanoseconds"
            unit == "By" -> "bytes"
            else -> unit.replace(Regex("[^a-zA-Z0-9]"), "_")
        }

    private companion object {
        /** From the OTLP resource: `job` is `service.name`, `instance` is `service.instance.id`. */
        val RESOURCE_LABELS = setOf("job", "instance")
    }
}
