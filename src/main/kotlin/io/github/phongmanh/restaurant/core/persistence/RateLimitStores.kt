package io.github.phongmanh.restaurant.core.persistence

import io.github.phongmanh.restaurant.core.application.RateLimiter
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Upstash Redis over its REST API (ADR-0008, measured in S-1 Q10): one `EVAL` per hit, so the
 * increment and the expiry are one round trip and one atomic step. The key names its window, so a
 * key that somehow missed its expiry still stops counting when the window ends.
 */
class UpstashRateLimiter(
    private val restUrl: String,
    private val token: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT.toJavaDuration()).build(),
) : RateLimiter {
    override suspend fun hit(
        key: String,
        window: Duration,
    ): Long {
        val command = JsonArray(listOf("EVAL", SCRIPT, "1", key, (window.inWholeSeconds + 1).toString()).map(::JsonPrimitive))
        val body = send(command.toString())
        return Json
            .parseToJsonElement(body)
            .jsonObject["result"]
            ?.jsonPrimitive
            ?.longOrNull ?: throw IOException("upstash answered without a count")
    }

    /**
     * Opens the connection before the first request needs it (S-1 Q10 design rule): a new
     * instance's first check otherwise took longer than the timeout and failed open.
     */
    suspend fun warmUp() {
        send("""["PING"]""")
    }

    private suspend fun send(body: String): String {
        val request =
            HttpRequest
                .newBuilder(URI(restUrl))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() != HTTP_OK) throw IOException("upstash answered ${response.statusCode()}")
        return response.body()
    }

    override fun toString(): String = "UpstashRateLimiter(***)"

    private companion object {
        const val HTTP_OK = 200
        val CONNECT_TIMEOUT = 2.seconds
        const val SCRIPT = "local n = redis.call('INCR', KEYS[1]) if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end return n"
    }
}

/**
 * Counters in this instance's memory, for local development and tests, where there's one instance
 * and no Upstash. Never for a deployed environment: each instance would count on its own.
 */
class LocalRateLimiter(
    private val clock: Clock,
) : RateLimiter {
    private val mutex = Mutex()
    private val counters = HashMap<String, Pair<Long, Instant>>()

    override suspend fun hit(
        key: String,
        window: Duration,
    ): Long =
        mutex.withLock {
            val now = clock.instant()
            counters.entries.removeIf { it.value.second <= now }
            val (count, expires) = counters[key] ?: (0L to now.plus(window.toJavaDuration()))
            (count + 1).also { counters[key] = it to expires }
        }
}
