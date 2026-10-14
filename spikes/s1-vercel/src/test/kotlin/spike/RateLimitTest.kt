package spike

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RateLimitTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = defaultHttpClient()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val baseUrl = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stop() = server.stop(0)

    /** A fake Upstash REST endpoint: counts `EVAL` calls per key, like the fixed-window script. */
    private fun fakeUpstash(token: String = "right"): Map<String, Int> {
        val counts = ConcurrentHashMap<String, Int>()
        server.createContext("/") { exchange ->
            if (exchange.requestHeaders.getFirst("Authorization") != "Bearer $token") {
                exchange.reply(401, """{"error":"WRONGPASS invalid or missing auth token"}""")
                return@createContext
            }
            val command = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonArray
            val key = command[3].jsonPrimitive.content
            exchange.reply(200, """{"result":${counts.merge(key, 1, Int::plus)}}""")
        }
        return counts
    }

    @Test
    fun `upstash - hits past the limit - denied with the seconds left in the window`() = runBlocking {
        fakeUpstash()
        val limiter = UpstashRestLimiter(baseUrl, "right", http, nowMs = { 125_000 })

        val verdicts = (1..4).map { limiter.hit("t1", limit = 3, windowSeconds = 60) }

        assertEquals(listOf(true, true, true, false), verdicts.map { it.allowed })
        assertEquals(4, verdicts.last().count)
        assertEquals(55, verdicts.last().retryAfterSeconds) // 125 s is 5 s into the window 120–180 s
        assertNull(verdicts.first().retryAfterSeconds)
    }

    @Test
    fun `upstash - next window - counts from one again`() = runBlocking {
        val counts = fakeUpstash()
        var now = 119_000L
        val limiter = UpstashRestLimiter(baseUrl, "right", http, nowMs = { now })

        limiter.hit("t1", limit = 1, windowSeconds = 60)
        now = 120_000L
        val next = limiter.hit("t1", limit = 1, windowSeconds = 60)

        assertTrue(next.allowed)
        assertEquals(setOf("spike:rl:t1:1", "spike:rl:t1:2"), counts.keys)
    }

    @Test
    fun `upstash - wrong token - store exception`() = runBlocking {
        fakeUpstash(token = "right")
        val limiter = UpstashRestLimiter(baseUrl, "wrong", http)

        val error = assertFailsWith<StoreException> { limiter.hit("t1", limit = 3, windowSeconds = 60) }

        assertEquals("upstash answered 401", error.message)
    }

    @Test
    fun `firewall - sends the SDK's headers and maps 204, 429 and 404`() = runBlocking {
        val statuses = ArrayDeque(listOf(204, 429, 404))
        val seen = mutableListOf<HttpExchange>()
        server.createContext("/.well-known/vercel/rate-limit-api/spike-keyed") { exchange ->
            seen += exchange
            exchange.sendResponseHeaders(statuses.removeFirst(), -1)
            exchange.close()
        }
        val limiter = VercelFirewallLimiter(baseUrl, "spike-keyed", "s3cret", protectionBypass = "bypass", http = http)

        val allowed = limiter.hit("t1", limit = 10, windowSeconds = 60)
        val denied = limiter.hit("t1", limit = 10, windowSeconds = 60)
        assertFailsWith<StoreException> { limiter.hit("t1", limit = 10, windowSeconds = 60) }

        assertTrue(allowed.allowed)
        assertFalse(denied.allowed)
        assertEquals(60, denied.retryAfterSeconds)
        val headers = seen.first().requestHeaders
        assertEquals("spike-keyed", headers.getFirst("x-vercel-rate-limit-api"))
        assertEquals("t1-${sha256("t1spike-keyedbypasss3cret")}", headers.getFirst("x-vercel-rate-limit-key"))
        assertEquals("bypass", headers.getFirst("x-vercel-protection-bypass"))
    }

    @Test
    fun `check - store slower than the timeout - fails open`() = runBlocking {
        val slow = object : Limiter {
            override suspend fun hit(key: String, limit: Long, windowSeconds: Long): Verdict {
                delay(1_000)
                return Verdict(allowed = false, count = 99, retryAfterSeconds = 1)
            }
        }

        val result = checkLimit("i1", "slow", slow, "t1", limit = 10, windowSeconds = 60, timeoutMs = 50)

        assertTrue(result.allowed)
        assertTrue(result.failedOpen)
        assertEquals("no answer within 50 ms", result.error)
        assertTrue(result.checkMs < 1_000)
    }

    @Test
    fun `check - store throws - fails open with the error`() = runBlocking {
        val result = checkLimit("i1", "broken", Throwing, "t1", limit = 10, windowSeconds = 60, timeoutMs = 200)

        assertTrue(result.allowed)
        assertTrue(result.failedOpen)
        assertEquals("StoreException: down", result.error)
    }

    @Test
    fun `limit endpoint - denied - 429 with Retry-After and the limiter header`() = testApplication {
        application {
            spikeModule(InstanceStats(), Dispatchers.Unconfined, env = { null }, limiters = mapOf("fake" to Denying))
        }

        val response = client.get("/spike/limit?store=fake&key=table-7&limit=10&window=60")

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("42", response.headers[HttpHeaders.RetryAfter])
        assertEquals("fake", response.headers["x-spike-limiter"])
        val result = json.decodeFromString<LimitResult>(response.bodyAsText())
        assertFalse(result.allowed)
        assertEquals(11, result.count)
    }

    @Test
    fun `limit endpoint - store failing - 200, failed open`() = testApplication {
        application {
            spikeModule(InstanceStats(), Dispatchers.Unconfined, env = { null }, limiters = mapOf("down" to Throwing))
        }

        val response = client.get("/spike/limit?store=down&key=t1")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(json.decodeFromString<LimitResult>(response.bodyAsText()).failedOpen)
    }

    @Test
    fun `limit endpoint - unknown store, bad key or bad window - 400`() = testApplication {
        application {
            spikeModule(InstanceStats(), Dispatchers.Unconfined, env = { null }, limiters = mapOf("fake" to Denying))
        }

        listOf(
            "/spike/limit?key=t1",
            "/spike/limit?store=redis&key=t1",
            "/spike/limit?store=fake",
            "/spike/limit?store=fake&key=a:b",
            "/spike/limit?store=fake&key=t1&window=5",
        ).forEach { assertEquals(HttpStatusCode.BadRequest, client.get(it).status, it) }
    }

    @Test
    fun `waf endpoint - answers with the instance`() = testApplication {
        val stats = InstanceStats()
        application { spikeModule(stats, Dispatchers.Unconfined, env = { null }) }

        val probe = json.decodeFromString<WafProbe>(client.get("/spike/waf").bodyAsText())

        assertEquals(stats.instanceId, probe.instanceId)
    }

    @Test
    fun `limiters from env - stores present only when their variables are`() {
        assertEquals(setOf("redis-down-timeout"), limitersFromEnv({ null }, http).keys)

        val all = mapOf("KV_REST_API_URL" to baseUrl, "KV_REST_API_TOKEN" to "t", "VERCEL_URL" to "x.vercel.app")
        assertEquals(
            setOf("redis-down-timeout", "redis", "redis-down-error", "firewall"),
            limitersFromEnv(all::get, http).keys,
        )
    }

    private object Denying : Limiter {
        override suspend fun hit(key: String, limit: Long, windowSeconds: Long) =
            Verdict(allowed = false, count = limit + 1, retryAfterSeconds = 42)
    }

    private object Throwing : Limiter {
        override suspend fun hit(key: String, limit: Long, windowSeconds: Long): Verdict = throw StoreException("down")
    }

    private fun HttpExchange.reply(status: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}
