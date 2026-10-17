package io.github.phongmanh.restaurant.core.observability

import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.orders.floor
import io.github.phongmanh.restaurant.orders.guestOrder
import io.github.phongmanh.restaurant.orders.line
import io.github.phongmanh.restaurant.orders.orderBody
import io.github.phongmanh.restaurant.orders.ordersApp
import io.github.phongmanh.restaurant.realtime.application.RealtimePublisher
import io.github.phongmanh.restaurant.support.FakeAbly
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.TestKeys
import io.github.phongmanh.restaurant.support.str
import io.github.phongmanh.restaurant.tables.job
import io.github.phongmanh.restaurant.tables.qrToken
import io.github.phongmanh.restaurant.tables.seat
import io.github.phongmanh.restaurant.tables.table
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.koin.dsl.module
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The domain meters (architecture §13, NFR-OBS-01): what the alerts and dashboards of step 4c read.
 * Each counts only what was saved, and none carries an ID.
 */
class ServiceMetersTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    @Test
    fun `scans, orders, publishing, the outbox and the reconcile are counted, once each`() {
        val registry = SimpleMeterRegistry()
        val ably = FakeAbly()
        ordersApp(
            overrides =
                listOf(
                    module {
                        single { ServiceMeters(registry) }
                        single<RealtimePublisher> { ably }
                    },
                ),
        ) {
            val f = client.floor()
            val code = client.qrToken(f.manager, client.table(f.manager, "M-1").str("id"))
            client.seat(code)
            client.seat(code)
            assertEquals(1.0, registry.count("sessions.opened"), "a scan that joins an open session opens none")

            val key = UuidV7.next().toString()
            val body = orderBody(line(f.tea))
            repeat(2) { assertEquals(201, client.guestOrder(f.guest, body, key).status.value, "order and its replay") }
            assertEquals(1.0, registry.count("orders.placed", "by", "guest"), "a replayed key's answer isn't a second order")
            assertTrue(registry.count("realtime.publish", "path", "inline", "result", "sent") >= 1)
            assertTrue(assertNotNull(registry.find("realtime.feed.delay").timer()).count() >= 1, "the feed delay is timed")

            ably.down = true
            assertEquals(201, client.guestOrder(f.guest, body).status.value)
            assertTrue(registry.count("realtime.publish", "path", "inline", "result", "failed") >= 1)
            assertEquals(200, client.job(Endpoints.Jobs.OUTBOX_SWEEP, TestKeys.CRON_SECRET).status.value)
            assertTrue(registry.gauge("outbox.pending") >= 1, "what Ably refused waits in the outbox")
            assertTrue(registry.gauge("outbox.oldest.age.seconds") >= 0)

            assertEquals(200, client.job(Endpoints.Jobs.SALES_RECONCILE, TestKeys.CRON_SECRET).status.value)
            assertEquals(0.0, registry.count("reports.reconcile.failures"))
            assertEquals(0.0, registry.count("reports.reconcile.mismatches"))
            assertNotNull(registry.find("reports.reconcile.days").counter(), "the run is counted")
        }

        val tagKeys =
            registry.meters
                .filter { !it.id.name.startsWith("ktor.") && !it.id.name.startsWith("db.") }
                .flatMap { meter -> meter.id.tags.map { it.key } }
                .toSet()
        // `le` is the feed delay's SLO bucket.
        assertTrue(tagKeys.all { it in setOf("path", "result", "by", "le") }, "no ID as a tag: $tagKeys")
    }

    private fun MeterRegistry.count(
        name: String,
        vararg tags: String,
    ): Double =
        find(name)
            .tags(*tags)
            .counter()
            ?.count() ?: 0.0

    private fun MeterRegistry.gauge(name: String): Double = assertNotNull(find(name).gauge(), name).value()
}
