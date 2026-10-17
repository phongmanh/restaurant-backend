package io.github.phongmanh.restaurant.staging

import io.github.phongmanh.restaurant.core.observability.Metrics
import io.github.phongmanh.restaurant.core.observability.ObservabilitySettings
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import kotlin.system.exitProcess

/**
 * ST-07's live half (test-plan §4, §9 step 4): pushes request counts with an injected 5xx share to
 * Grafana Cloud, through the same OTLP registry the service uses, so the alerts on staging can be
 * seen to fire. The service has no way to fail on purpose, so the injected errors are synthetic
 * series; they carry `client="st-07"`, which no real caller has, so a query can leave them out.
 * The rest of the chain is tested offline: the service counts a 5xx as `status="5xx"`
 * (MetricsTest), Grafana's names are the ones the rules read (MetricNamesTest), and the rules fire at
 * their burn rates (AlertRulesTest).
 *
 * `./gradlew injectErrors --args="--minutes 20 --error-ratio 0.05"`, with the environment exported
 * from `deploy/staging/.env.staging` (deploy/README.md). It sends no request to the service, so it
 * spends none of Vercel's allowance (test-plan §9, budget guard).
 */
fun main(args: Array<String>) {
    val options = args.toList().chunked(2).associate { it.first() to it.getOrNull(1) }
    val minutes = options["--minutes"]?.toLongOrNull() ?: DEFAULT_MINUTES
    val errorRatio = options["--error-ratio"]?.toDoubleOrNull() ?: DEFAULT_ERROR_RATIO
    val perSecond = options["--rps"]?.toIntOrNull() ?: DEFAULT_RPS
    if (minutes !in 1..MAX_MINUTES || errorRatio !in 0.0..1.0 || perSecond !in 1..MAX_RPS) {
        println("usage: injectErrors --args=\"[--minutes 1-$MAX_MINUTES] [--error-ratio 0-1] [--rps 1-$MAX_RPS]\"")
        exitProcess(2)
    }
    val step = Duration.ofSeconds(STEP_S)
    val settings =
        ObservabilitySettings(
            env("OTEL_EXPORTER_OTLP_ENDPOINT"),
            ObservabilitySettings.parseHeaders(env("OTEL_EXPORTER_OTLP_HEADERS")),
            step,
        )
    val seconds = minutes * SECONDS_PER_MINUTE
    println(
        "injectErrors: $minutes min of $perSecond guest menu reads a second, ${errorRatio * PERCENT}% of them 5xx; " +
            "${seconds / STEP_S} OTLP pushes, no request to the service",
    )
    val registry = Metrics.registry(settings, "staging")
    val ok = timer(registry, "200")
    val failed = timer(registry, "500")
    var errorsOwed = 0.0
    repeat(seconds.toInt()) { second ->
        repeat(perSecond) {
            errorsOwed += errorRatio
            if (errorsOwed >= 1) {
                errorsOwed -= 1
                failed.record(Duration.ofMillis(LATENCY_MS))
            } else {
                ok.record(Duration.ofMillis(LATENCY_MS))
            }
        }
        val done = second + 1
        if (done % SECONDS_PER_MINUTE == 0L) println("  minute ${done / SECONDS_PER_MINUTE}: ${ok.count()} ok, ${failed.count()} 5xx")
        Thread.sleep(Duration.ofSeconds(1))
    }
    // Closing pushes what the last step hasn't sent yet.
    registry.close()
    println("injectErrors: done. At 5% errors, AvailabilityBurnFast fires ~5 min after the start, AvailabilityBurnSlow ~17 min")
}

/** The service's HTTP timer, with the tags Ktor gives it; the registry's filter turns 200 into 2xx. */
@Suppress("SpreadOperator")
private fun timer(
    registry: MeterRegistry,
    status: String,
): Timer =
    Timer
        .builder("ktor.http.server.requests")
        .tags("method", "GET", "route", "/v1/guest/menu", "status", status, "group", "guest", "client", "st-07")
        .serviceLevelObjectives(*Metrics.SLO_BUCKETS.toTypedArray())
        .register(registry)

private const val SECONDS_PER_MINUTE = 60L
private const val PERCENT = 100
private const val DEFAULT_MINUTES = 20L
private const val MAX_MINUTES = 120L
private const val DEFAULT_ERROR_RATIO = 0.05
private const val DEFAULT_RPS = 2
private const val MAX_RPS = 50
private const val STEP_S = 10L
private const val LATENCY_MS = 50L
