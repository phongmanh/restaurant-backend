package spike

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ServerReady
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.TimeSource

private val log = LoggerFactory.getLogger("spike")

/** Request headers worth seeing behind Vercel's proxy. Never echo all of them: cookies, tokens. */
private val PROXY_HEADERS = listOf(
    "x-forwarded-for",
    "x-forwarded-proto",
    "x-forwarded-host",
    "x-real-ip",
    "x-vercel-forwarded-for",
    "x-vercel-id",
    "x-vercel-deployment-url",
    "x-vercel-ip-country",
)

/** Vercel system variables that identify the deployment. None of them is a secret. */
private val PLATFORM_VARIABLES = listOf("VERCEL_REGION", "VERCEL_ENV", "VERCEL_DEPLOYMENT_ID", "VERCEL_GIT_COMMIT_SHA")

private const val MAX_SLEEP_MS = 3_600_000L
private const val MAX_CPU_MS = 10_000L
private const val MAX_SSE_SECONDS = 3_600L
private const val BYTES_PER_MB = 1_048_576L
private val LIMIT_KEY = Regex("[A-Za-z0-9_-]{1,64}")

/**
 * The spike's endpoints. Each one answers one S-1 question; see the README.
 *
 * @param cpuDispatcher where `/spike/cpu` burns CPU, so it never blocks a Netty event loop.
 * @param env reads environment variables; replaced in tests.
 * @param limiters the rate-limit stores `/spike/limit` can use, by name (see [limitersFromEnv]).
 */
fun Application.spikeModule(
    stats: InstanceStats,
    cpuDispatcher: CoroutineDispatcher,
    env: (String) -> String? = System::getenv,
    limiters: Map<String, Limiter> = emptyMap(),
) {
    install(ContentNegotiation) { json() }

    monitor.subscribe(ServerReady) {
        stats.markReady()
        log.info("spike ready {}", stats.startupSummary())
    }

    intercept(ApplicationCallPipeline.Monitoring) {
        stats.requestStarted()
        try {
            proceed()
        } finally {
            stats.requestFinished()
        }
    }

    routing {
        get("/health/live") { call.respondText("ok") }

        // Cold start: firstRequestOnInstance + startupMs. Region, limits and proxy headers too.
        get("/spike/info") { call.respond(instanceInfo(call, stats, env)) }

        // Concurrency per instance (inFlightAtStart) and the maximum request duration.
        get("/spike/sleep") {
            val ms = call.boundedLong("ms", 1..MAX_SLEEP_MS) ?: return@get
            val inFlightAtStart = stats.snapshot().inFlight
            val mark = TimeSource.Monotonic.markNow()
            delay(ms)
            call.respond(SleepResult(stats.instanceId, ms, mark.elapsedNow().inWholeMilliseconds, inFlightAtStart))
        }

        // CPU share: how many loop iterations the instance manages in the given time.
        get("/spike/cpu") {
            val ms = call.boundedLong("ms", 1..MAX_CPU_MS) ?: return@get
            val iterations = withContext(cpuDispatcher) { burnCpu(ms) }
            call.respond(CpuResult(stats.instanceId, ms, iterations))
        }

        // Rate limiting (ADR-0008): one fixed-window hit on a key in the chosen store, timed.
        // A 429 from here carries x-spike-limiter, so it can't be mistaken for the WAF's.
        get("/spike/limit") {
            val store = call.request.queryParameters["store"].orEmpty()
            val limiter = limiters[store] ?: return@get call.respondText(
                "'store' must be one of ${limiters.keys.sorted()} on this deployment",
                status = HttpStatusCode.BadRequest,
            )
            val key = call.request.queryParameters["key"]?.takeIf(LIMIT_KEY::matches)
                ?: return@get call.respondText("'key' must match $LIMIT_KEY", status = HttpStatusCode.BadRequest)
            val limit = call.boundedLong("limit", 1..1_000_000L, default = 10) ?: return@get
            val window = call.boundedLong("window", 10..600L, default = 60) ?: return@get
            val timeoutMs = call.boundedLong("timeoutMs", 10..5_000L, default = 200) ?: return@get
            val result = checkLimit(stats.instanceId, store, limiter, key, limit, window, timeoutMs)
            call.response.header("x-spike-limiter", store)
            if (result.allowed) {
                call.respond(result)
            } else {
                result.retryAfterSeconds?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
                call.respond(HttpStatusCode.TooManyRequests, result)
            }
        }

        // Target of the edge WAF rule (Q9). Never limited here, so any 429 on it came from the WAF.
        get("/spike/waf") { call.respond(WafProbe(stats.instanceId)) }

        // Streaming: does each event arrive when it is sent, and how long may a stream stay open?
        get("/spike/sse") {
            val seconds = call.boundedLong("seconds", 1..MAX_SSE_SECONDS) ?: return@get
            val intervalMs = call.boundedLong("intervalMs", 100..60_000L, default = 1_000) ?: return@get
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.response.header("X-Accel-Buffering", "no")
            call.respondTextWriter(ContentType.Text.EventStream) {
                val mark = TimeSource.Monotonic.markNow()
                var n = 0
                while (mark.elapsedNow().inWholeSeconds < seconds) {
                    val elapsed = mark.elapsedNow().inWholeMilliseconds
                    write("id: $n\ndata: {\"n\":$n,\"elapsedMs\":$elapsed,\"instanceId\":\"${stats.instanceId}\"}\n\n")
                    flush()
                    n++
                    delay(intervalMs)
                }
                write("event: end\ndata: {\"n\":$n,\"elapsedMs\":${mark.elapsedNow().inWholeMilliseconds}}\n\n")
                flush()
            }
        }
    }
}

/**
 * Reads a whole-number query parameter within [range]. On a missing or bad value it answers 400
 * and returns null, so the caller just returns.
 */
private suspend fun ApplicationCall.boundedLong(name: String, range: LongRange, default: Long? = null): Long? {
    val raw = request.queryParameters[name]
    val value = raw?.toLongOrNull() ?: default.takeIf { raw == null }
    if (value == null || value !in range) {
        respondText("'$name' must be a whole number in $range", status = HttpStatusCode.BadRequest)
        return null
    }
    return value
}

private fun burnCpu(ms: Long): Long {
    val mark = TimeSource.Monotonic.markNow()
    var iterations = 0L
    var x = 1L
    while (mark.elapsedNow().inWholeMilliseconds < ms) {
        repeat(10_000) { x = x * 6_364_136_223_846_793_005L + 1 }
        iterations++
    }
    // Use x so the JIT can't drop the loop.
    return if (x == 0L) -iterations else iterations
}

private fun instanceInfo(call: ApplicationCall, stats: InstanceStats, env: (String) -> String?): InstanceInfo {
    val snapshot = stats.snapshot()
    val runtime = Runtime.getRuntime()
    return InstanceInfo(
        instanceId = stats.instanceId,
        firstRequestOnInstance = stats.claimFirstRequest(),
        jvmStartedAtEpochMs = stats.jvmStartedAtMs,
        startupMs = snapshot.startupMs,
        uptimeMs = snapshot.uptimeMs,
        requestsServed = snapshot.requestsServed,
        inFlight = snapshot.inFlight,
        maxInFlightSeen = snapshot.maxInFlightSeen,
        runtime = RuntimeInfo(
            javaVersion = System.getProperty("java.version"),
            vmName = System.getProperty("java.vm.name"),
            availableProcessors = runtime.availableProcessors(),
            maxHeapMb = runtime.maxMemory() / BYTES_PER_MB,
            containerMemoryLimitMb = containerMemoryLimitBytes()?.div(BYTES_PER_MB),
            garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans().map { it.name },
            jvmArguments = ManagementFactory.getRuntimeMXBean().inputArguments,
        ),
        platform = PLATFORM_VARIABLES.mapNotNull { name -> env(name)?.let { name to it } }.toMap(),
        request = RequestInfo(
            remoteHost = call.request.origin.remoteHost,
            proxyHeaders = PROXY_HEADERS.mapNotNull { name -> call.request.header(name)?.let { name to it } }.toMap(),
        ),
    )
}

/** The cgroup memory limit (v2, then v1), or null when there is none or it can't be read. */
private fun containerMemoryLimitBytes(): Long? =
    listOf("/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory/memory.limit_in_bytes")
        .map(::File)
        .firstOrNull { it.canRead() }
        ?.readText()
        ?.trim()
        ?.toLongOrNull()

@Serializable
data class InstanceInfo(
    val instanceId: String,
    val firstRequestOnInstance: Boolean,
    val jvmStartedAtEpochMs: Long,
    val startupMs: Long?,
    val uptimeMs: Long,
    val requestsServed: Long,
    val inFlight: Int,
    val maxInFlightSeen: Int,
    val runtime: RuntimeInfo,
    val platform: Map<String, String>,
    val request: RequestInfo,
)

@Serializable
data class RuntimeInfo(
    val javaVersion: String,
    val vmName: String,
    val availableProcessors: Int,
    val maxHeapMb: Long,
    val containerMemoryLimitMb: Long?,
    val garbageCollectors: List<String>,
    val jvmArguments: List<String>,
)

@Serializable
data class RequestInfo(
    val remoteHost: String,
    val proxyHeaders: Map<String, String>,
)

@Serializable
data class SleepResult(
    val instanceId: String,
    val requestedMs: Long,
    val actualMs: Long,
    val inFlightAtStart: Int,
)

@Serializable
data class CpuResult(
    val instanceId: String,
    val ms: Long,
    val iterations: Long,
)

@Serializable
data class WafProbe(val instanceId: String)
