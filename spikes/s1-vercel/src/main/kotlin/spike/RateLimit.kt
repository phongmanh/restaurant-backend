package spike

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import kotlin.time.TimeSource

private val log = LoggerFactory.getLogger("spike")

/**
 * The `RateLimiter` port from ADR-0008, cut down to what the spike measures: one fixed-window
 * counter per key, shared by every instance.
 */
interface Limiter {
    /** Counts one hit on [key]. Throws when the store can't answer; [checkLimit] then fails open. */
    suspend fun hit(key: String, limit: Long, windowSeconds: Long): Verdict
}

/** [count] and [retryAfterSeconds] are null when the store doesn't say. */
data class Verdict(val allowed: Boolean, val count: Long?, val retryAfterSeconds: Long?)

class StoreException(message: String) : Exception(message)

/**
 * Upstash Redis over its REST API: one `EVAL` per hit, so the increment and the expiry are one
 * round trip and one atomic step. The window number is part of the key, so a key that somehow
 * misses its expiry still stops counting when the window ends.
 */
class UpstashRestLimiter(
    private val restUrl: String,
    private val token: String,
    private val http: HttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Limiter {
    override suspend fun hit(key: String, limit: Long, windowSeconds: Long): Verdict {
        val nowSeconds = nowMs() / 1_000
        val redisKey = "spike:rl:$key:${nowSeconds / windowSeconds}"
        val command = buildJsonArray {
            add("EVAL")
            add(FIXED_WINDOW_SCRIPT)
            add("1")
            add(redisKey)
            add((windowSeconds + 1).toString())
        }
        val request = HttpRequest.newBuilder(URI(restUrl))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(command.toString()))
            .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() != 200) throw StoreException("upstash answered ${response.statusCode()}")
        val count = Json.parseToJsonElement(response.body()).jsonObject["result"]?.jsonPrimitive?.longOrNull
            ?: throw StoreException("upstash answered without a numeric result")
        val allowed = count <= limit
        return Verdict(allowed, count, if (allowed) null else windowSeconds - nowSeconds % windowSeconds)
    }

    private companion object {
        const val FIXED_WINDOW_SCRIPT =
            "local n = redis.call('INCR', KEYS[1]) " +
                "if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
                "return n"
    }
}

/**
 * Vercel WAF rate limiting called from Kotlin. `@vercel/firewall`'s `checkRateLimit` (JS only) is a
 * plain GET to `/.well-known/vercel/rate-limit-api/<id>` on the deployment's host; this sends the
 * same request (package version 1.2.6), minus the copied client headers, which only matter for rule
 * conditions on request headers. The limit and window live in the dashboard rule, not here: the API
 * answers 204 (allowed) or 429 (limited), with no count and no reset time.
 */
class VercelFirewallLimiter(
    private val baseUrl: String,
    private val rateLimitId: String,
    private val keySecret: String,
    private val protectionBypass: String?,
    private val http: HttpClient,
) : Limiter {
    override suspend fun hit(key: String, limit: Long, windowSeconds: Long): Verdict {
        val id = URLEncoder.encode(rateLimitId, Charsets.UTF_8)
        // The suffix makes a key unguessable for anyone without the secrets, as the SDK does.
        val fullKey = "$key-${sha256Hex(key + rateLimitId + (protectionBypass ?: "") + keySecret)}"
        val request = HttpRequest.newBuilder(URI("$baseUrl/.well-known/vercel/rate-limit-api/$id"))
            .header("x-vercel-rate-limit-api", rateLimitId)
            .header("x-vercel-rate-limit-key", fullKey)
            .header("user-agent", "Bot/Vercel Rate Limit Checker")
            .apply { protectionBypass?.let { header("x-vercel-protection-bypass", it) } }
            .GET()
            .build()
        return when (val status = http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await().statusCode()) {
            204 -> Verdict(allowed = true, count = null, retryAfterSeconds = null)
            // No reset time from the API, so a client is told to wait a whole window.
            429 -> Verdict(allowed = false, count = null, retryAfterSeconds = windowSeconds)
            403 -> Verdict(allowed = false, count = null, retryAfterSeconds = null)
            404 -> throw StoreException("rate-limit ID '$rateLimitId' is not configured in the firewall")
            else -> throw StoreException("firewall answered $status")
        }
    }
}

/**
 * One hit on [limiter], timed. When the store fails or takes longer than [timeoutMs], the request
 * is let through (fail open, ADR-0008) and a warning is logged: the WAF still guards the edge.
 */
suspend fun checkLimit(
    instanceId: String,
    store: String,
    limiter: Limiter,
    key: String,
    limit: Long,
    windowSeconds: Long,
    timeoutMs: Long,
): LimitResult {
    val mark = TimeSource.Monotonic.markNow()
    var error: String? = null
    val verdict = try {
        withTimeoutOrNull(timeoutMs) { limiter.hit(key, limit, windowSeconds) }
            .also { if (it == null) error = "no answer within $timeoutMs ms" }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = "${e::class.simpleName}: ${e.message}"
        null
    }
    val checkMs = mark.elapsedNow().inWholeMicroseconds / 1_000.0
    if (verdict == null) log.warn("rate limit store {} failed open after {} ms: {}", store, checkMs, error)
    return LimitResult(
        instanceId = instanceId,
        store = store,
        allowed = verdict?.allowed ?: true,
        failedOpen = verdict == null,
        count = verdict?.count,
        limit = limit,
        retryAfterSeconds = verdict?.retryAfterSeconds,
        checkMs = checkMs,
        error = error,
    )
}

/**
 * The stores this deployment can use, from its environment. Upstash's Vercel Marketplace
 * integration names its variables `KV_REST_API_*` or `UPSTASH_REDIS_REST_*`; both are read.
 * `redis-down-timeout` points at an address nothing answers on, and `redis-down-error` sends a
 * wrong token, so the two ways a store can fail are both reproducible.
 */
fun limitersFromEnv(env: (String) -> String?, http: HttpClient = defaultHttpClient()): Map<String, Limiter> = buildMap {
    put("redis-down-timeout", UpstashRestLimiter("https://10.255.255.1", "unused", http))
    val restUrl = env("UPSTASH_REDIS_REST_URL") ?: env("KV_REST_API_URL")
    val token = env("UPSTASH_REDIS_REST_TOKEN") ?: env("KV_REST_API_TOKEN")
    if (restUrl != null && token != null) {
        put("redis", UpstashRestLimiter(restUrl, token, http))
        put("redis-down-error", UpstashRestLimiter(restUrl, "not-the-token", http))
    }
    env("VERCEL_URL")?.let { host ->
        put(
            "firewall",
            VercelFirewallLimiter(
                baseUrl = "https://$host",
                rateLimitId = env("SPIKE_RATE_LIMIT_ID") ?: "spike-keyed",
                keySecret = env("RATE_LIMIT_SECRET") ?: "",
                protectionBypass = env("VERCEL_AUTOMATION_BYPASS_SECRET"),
                http = http,
            ),
        )
    }
}

/** One client per instance, so connections to the store are reused between requests. */
fun defaultHttpClient(): HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

private fun sha256Hex(input: String): String =
    MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

@Serializable
data class LimitResult(
    val instanceId: String,
    val store: String,
    val allowed: Boolean,
    val failedOpen: Boolean,
    val count: Long?,
    val limit: Long,
    val retryAfterSeconds: Long?,
    val checkMs: Double,
    val error: String?,
)
