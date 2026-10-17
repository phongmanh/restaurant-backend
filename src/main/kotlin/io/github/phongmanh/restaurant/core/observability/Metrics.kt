package io.github.phongmanh.restaurant.core.observability

import io.github.phongmanh.restaurant.core.http.X_CLIENT_HEADER
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.registry.otlp.OtlpConfig
import io.micrometer.registry.otlp.OtlpMeterRegistry
import java.time.Duration
import java.util.UUID

/** Where metrics go (ADR-0009): an OTLP endpoint and its headers, or nowhere in dev and test. */
class ObservabilitySettings(
    val otlpEndpoint: String?,
    val headers: Map<String, String>,
    val step: Duration,
) {
    /** The headers carry the backend's token, so they never print. */
    override fun toString(): String = "ObservabilitySettings(otlpEndpoint=$otlpEndpoint, headers=${headers.keys})"

    companion object {
        /** Dev and test: count in memory, push nowhere. */
        val NONE = ObservabilitySettings(null, emptyMap(), Duration.ofSeconds(10))

        /** `"Authorization=Basic abc,X-Other=1"` → a map; a value may contain `=`. */
        fun parseHeaders(text: String): Map<String, String> =
            text
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .associate { pair ->
                    require('=' in pair) { "OTEL_EXPORTER_OTLP_HEADERS isn't name=value pairs" }
                    pair.substringBefore('=').trim() to pair.substringAfter('=').trim()
                }
    }
}

/**
 * The service's meter registry (architecture §13, ADR-0009). Outside dev and test it pushes over
 * OTLP every [ObservabilitySettings.step], from every instance with its own `service.instance.id`,
 * and once more when the application stops. Without an endpoint it only counts in memory, which the
 * tests read.
 *
 * **Series stay few**, because the backend's free plan stores a limited number and every Vercel
 * instance has its own: no host tag, the status as its class (`2xx`), the route as its template (an
 * unknown path counts as `n/a`), and latency as a few buckets at the SLO thresholds instead of a full
 * histogram.
 */
object Metrics {
    const val SERVICE = "restaurant-backend"

    private const val GUEST_READ_P95_MS = 200L
    private const val WRITE_P95_MS = 300L
    private const val GUEST_READ_P99_MS = 500L
    private const val WRITE_P99_MS = 800L
    private const val REPORT_P95_MS = 3_000L

    /** The HTTP timer's buckets: NFR-PERF-01/02's p95 and p99 targets, and NFR-PERF-04's 3 s. */
    val SLO_BUCKETS: List<Duration> =
        listOf(GUEST_READ_P95_MS, WRITE_P95_MS, GUEST_READ_P99_MS, WRITE_P99_MS, REPORT_P95_MS).map(Duration::ofMillis)

    fun registry(
        settings: ObservabilitySettings,
        environment: String,
    ): MeterRegistry {
        val endpoint = settings.otlpEndpoint
        val registry =
            if (endpoint == null) {
                SimpleMeterRegistry()
            } else {
                OtlpMeterRegistry(otlpConfig(endpoint, settings, environment), Clock.SYSTEM)
            }
        registry
            .config()
            .meterFilter(MeterFilter.ignoreTags("address", "throwable", "exception"))
            .meterFilter(MeterFilter.replaceTagValues("status", { it.firstOrNull()?.let { c -> "${c}xx" } ?: "unknown" }))
        return registry
    }

    /** The HTTP timer's distribution: counts at the SLO thresholds only. Built once, at startup. */
    @Suppress("SpreadOperator")
    fun httpDistribution(): DistributionStatisticConfig =
        DistributionStatisticConfig
            .builder()
            .percentilesHistogram(false)
            .serviceLevelObjectives(*SLO_BUCKETS.map { it.toNanos().toDouble() }.toDoubleArray())
            .build()

    /** The path group, as the authentication rules split them (core-and-auth §2). */
    fun group(path: String): String =
        when {
            path.startsWith("/v1/guest") -> "guest"
            path.startsWith("/v1/staff") -> "staff"
            path.startsWith("/v1/admin") -> "admin"
            path.startsWith("/v1/auth") -> "auth"
            path.startsWith("/internal/jobs") -> "jobs"
            path == "/health" -> "health"
            else -> "other"
        }

    /**
     * The calling app from `X-Client` (core-and-auth §2: "recorded in metrics"): one of [known],
     * `other` for any other name, `none` without the header. Never the version, which would multiply
     * the series.
     */
    fun client(
        call: ApplicationCall,
        known: Set<String>,
    ): String {
        val app =
            call.request
                .header(X_CLIENT_HEADER)
                ?.substringBefore('/')
                ?.trim() ?: return "none"
        return if (app in known) app else "other"
    }

    private fun otlpConfig(
        endpoint: String,
        settings: ObservabilitySettings,
        environment: String,
    ): OtlpConfig {
        val instance = UUID.randomUUID().toString()
        return object : OtlpConfig {
            override fun get(key: String): String? = null

            override fun url(): String = endpoint.trimEnd('/') + "/v1/metrics"

            override fun headers(): Map<String, String> = settings.headers

            override fun step(): Duration = settings.step

            override fun resourceAttributes(): Map<String, String> =
                mapOf(
                    "service.name" to SERVICE,
                    "service.instance.id" to instance,
                    "deployment.environment" to environment,
                )
        }
    }
}

/**
 * RED metrics per route (architecture §13): Ktor's timer `ktor.http.server.requests`, tagged with
 * the route template, method, status class, path group and calling app; a few JVM meters; nothing
 * per host. An unregistered path counts under `route=n/a`, so scanners can't add series.
 */
fun Application.installMetrics(
    registry: MeterRegistry,
    knownClients: Set<String>,
) {
    val binders = listOf(UptimeMetrics(), ProcessorMetrics(), JvmMemoryMetrics(), JvmGcMetrics())
    // JvmGcMetrics registers JVM-wide GC listeners that hold the registry; closing it removes them.
    // Without this every stopped application stayed reachable, and the test suite ran out of heap.
    monitor.subscribe(ApplicationStopped) { binders.filterIsInstance<AutoCloseable>().forEach { it.close() } }
    install(MicrometerMetrics) {
        this.registry = registry
        meterBinders = binders
        distributionStatisticConfig = Metrics.httpDistribution()
        // false groups every unregistered path under route=n/a; true would tag each one by its URL.
        distinctNotRegisteredRoutes = false
        timers { call, _ ->
            tag("group", Metrics.group(call.request.path()))
            tag("client", Metrics.client(call, knownClients))
        }
    }
}
