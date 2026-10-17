package io.github.phongmanh.restaurant.core.observability

import io.github.phongmanh.restaurant.service
import io.github.phongmanh.restaurant.support.TestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.koin.ktor.ext.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The service's meters (architecture §13, ADR-0009): RED per route with few series, pool meters,
 * and a count of instance starts. Without an OTLP endpoint the registry counts in memory, which this
 * test reads.
 */
class MetricsTest {
    @Test
    fun `requests are timed per route with few tags, and pools and starts are counted`() {
        lateinit var registry: MeterRegistry
        testApplication {
            application {
                service(TestDatabase.config)
                registry = get()
                // A failure, as the availability SLO counts it (deploy/observability/rules.yaml).
                routing { get("/test/fails") { error("a bug") } }
            }
            client.get("/test/fails")
            client.get("/health") { header("X-Client", "customer-android/1.2.3") }
            client.get("/v1/staff/members") { header("X-Client", "some-script/0.1") }
            client.get("/a/path/nobody/registered")
            assertNotNull(registry.find("db.pool.connections.active").tag("pool", "app").gauge(), "the app pool reports")
            assertEquals(
                10.0,
                registry
                    .find("db.pool.connections.max")
                    .tag("pool", "app")
                    .gauge()
                    ?.value(),
            )
        }

        val timers = registry.find("ktor.http.server.requests").timers()
        assertTrue(timers.isNotEmpty(), "the HTTP timer exists")
        timers.forEach { timer ->
            val keys =
                timer.id.tags
                    .map { it.key }
                    .toSet()
            assertTrue("address" !in keys && "throwable" !in keys, "no per-host or per-exception tag: $keys")
            assertTrue(timer.id.getTag("status")!!.matches(Regex("[1-5]xx")), "status as its class: ${timer.id.tags}")
        }
        val health = timers.single { it.id.getTag("group") == "health" }
        assertEquals("customer-android", health.id.getTag("client"), "a known app is named, its version isn't")
        assertEquals("2xx", health.id.getTag("status"))
        val staff = timers.single { it.id.getTag("group") == "staff" }
        assertEquals("other", staff.id.getTag("client"), "an unknown app is counted as other")
        assertEquals("4xx", staff.id.getTag("status"))
        val unknown = timers.single { it.id.getTag("group") == "other" && it.id.getTag("route") == "n/a" }
        val failed = timers.single { it.id.getTag("route") == "/test/fails" }
        assertEquals("5xx", failed.id.getTag("status"), "an unhandled error counts as 5xx")
        assertEquals("n/a", unknown.id.getTag("route"), "an unregistered path adds no series of its own")
        assertEquals("none", unknown.id.getTag("client"))
        assertEquals(Metrics.SLO_BUCKETS.size, buckets(health), "latency is counted at the SLO thresholds only")

        assertEquals(1.0, registry.find("app.starts").counter()?.count())
    }

    private fun buckets(timer: Timer) = timer.takeSnapshot().histogramCounts().size
}
