package io.github.phongmanh.restaurant.core.auth

import com.sun.net.httpserver.HttpServer
import io.github.phongmanh.restaurant.core.application.RateLimitPolicy
import io.github.phongmanh.restaurant.core.application.RateLimiter
import io.github.phongmanh.restaurant.core.application.RateLimits
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.observability.ServiceMeters
import io.github.phongmanh.restaurant.core.persistence.LocalRateLimiter
import io.github.phongmanh.restaurant.core.persistence.UpstashRateLimiter
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.MutableClock
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.field
import io.github.phongmanh.restaurant.support.postJson
import io.github.phongmanh.restaurant.support.signIn
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** IT-AUTH-13 (core-and-auth §11, ADR-0008). */
class RateLimitTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    @Test
    fun `IT-AUTH-13 the 31st sign-in from one IP in a minute is 429 with Retry-After`() {
        val clock = MutableClock()
        authApp(clock, limiter = null) {
            repeat(30) { assertEquals(HttpStatusCode.Unauthorized, client.signIn("nobody@pho.example").status) }
            val limited = client.signIn("nobody@pho.example")
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("rate_limited", limited.field("code"))
            val retryAfter = limited.headers[HttpHeaders.RetryAfter]!!.toLong()
            assertTrue(retryAfter in 1..60, "Retry-After $retryAfter")

            clock.advance(retryAfter.seconds)
            assertEquals(HttpStatusCode.Unauthorized, client.signIn("nobody@pho.example").status, "the next window")
        }
    }

    /** Staging's WAF can't be trusted to let 121 refreshes through (ST-14, Q-20), so this is the check. */
    @Test
    fun `IT-AUTH-13 the 121st refresh from one IP in a minute is 429 with Retry-After`() {
        val clock = MutableClock()
        authApp(clock, limiter = null) {
            val refresh = """{"audience": "staff"}"""
            repeat(120) { assertEquals(HttpStatusCode.Unauthorized, client.postJson("/v1/auth/refresh", refresh).status) }
            val limited = client.postJson("/v1/auth/refresh", refresh)
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("rate_limited", limited.field("code"))
            val retryAfter = limited.headers[HttpHeaders.RetryAfter]!!.toLong()
            assertTrue(retryAfter in 1..60, "Retry-After $retryAfter")

            clock.advance(retryAfter.seconds)
            assertEquals(HttpStatusCode.Unauthorized, client.postJson("/v1/auth/refresh", refresh).status, "the next window")
        }
    }

    @Test
    fun `IT-AUTH-13 when the store fails, requests are served`() =
        authApp(limiter = { _, _ -> throw IOException("redis down") }) {
            AuthFixtures.staff(AuthFixtures.restaurant(), "lan@pho.example")
            repeat(31) { assertEquals(HttpStatusCode.OK, client.signIn("lan@pho.example").status) }
        }

    @Test
    fun `a store slower than the timeout, or failing, fails open and is counted`() =
        runBlocking {
            val registry = SimpleMeterRegistry()
            val meters = ServiceMeters(registry)
            val slow = RateLimits({ _, _ -> delay(1.seconds).let { 999L } }, MutableClock(), 50.milliseconds, meters)
            slow.check(RateLimitPolicy("p", 1, 60.seconds), "s")
            val failing = RateLimits({ _, _ -> throw IOException("down") }, MutableClock(), 1.seconds, meters)
            failing.check(RateLimitPolicy("p", 1, 60.seconds), "s")
            // ADR-0008's alert (RateLimitFailingOpen) reads this counter.
            listOf("timeout", "error").forEach { reason ->
                assertEquals(
                    1.0,
                    registry
                        .find("rate.limit.fail.open")
                        .tags("policy", "p", "reason", reason)
                        .counter()
                        ?.count(),
                    reason,
                )
            }
        }

    @Test
    fun `fixed windows count per key and reset with the window`() =
        runBlocking {
            val clock = MutableClock()
            val limits = RateLimits(LocalRateLimiter(clock), clock, 1.seconds)
            val policy = RateLimitPolicy("auth.mfa", 2, 60.seconds)
            limits.check(policy, "a")
            limits.check(policy, "a")
            limits.check(policy, "b")
            val e = assertFailsWith<DomainError.RateLimited> { limits.check(policy, "a") }
            assertTrue(e.retryAfterSeconds!! in 1..60)
            clock.advance(60.seconds)
            limits.check(policy, "a")
        }

    @Test
    fun `the Upstash adapter sends one EVAL with its token and reads the count`() {
        val bodies = CopyOnWriteArrayList<String>()
        val auth = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            bodies += exchange.requestBody.readAllBytes().decodeToString()
            auth += exchange.requestHeaders.getFirst("Authorization")
            val (status, answer) =
                if (auth.last() ==
                    "Bearer right"
                ) {
                    200 to """{"result": ${bodies.size}}"""
                } else {
                    401 to """{"error": "no"}"""
                }
            exchange.sendResponseHeaders(status, answer.length.toLong())
            exchange.responseBody.use { it.write(answer.toByteArray()) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            runBlocking {
                val limiter: RateLimiter = UpstashRateLimiter(url, "right")
                assertEquals(1, limiter.hit("rl:auth.sign_in:203.0.113.7:1760000000", 60.seconds))
                assertTrue(bodies[0].startsWith("""["EVAL","""), bodies[0])
                assertTrue(bodies[0].endsWith(""""1","rl:auth.sign_in:203.0.113.7:1760000000","61"]"""), bodies[0])
                assertEquals("Bearer right", auth[0])
                (limiter as UpstashRateLimiter).warmUp()
                assertEquals("""["PING"]""", bodies[1])

                assertFailsWith<IOException> { UpstashRateLimiter(url, "wrong").hit("k", 60.seconds) }
                // Through RateLimits a failing store lets the request through.
                RateLimits(UpstashRateLimiter(url, "wrong"), MutableClock(), 1.seconds).check(RateLimitPolicy("p", 0, 60.seconds), "s")
            }
        } finally {
            server.stop(0)
        }
    }
}
