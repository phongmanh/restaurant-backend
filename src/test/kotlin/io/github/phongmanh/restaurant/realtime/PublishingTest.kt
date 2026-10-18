package io.github.phongmanh.restaurant.realtime

import io.github.phongmanh.restaurant.core.application.RateLimiter
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.config.LivePush
import io.github.phongmanh.restaurant.core.config.RealtimeSettings
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.orders.floor
import io.github.phongmanh.restaurant.orders.line
import io.github.phongmanh.restaurant.orders.placed
import io.github.phongmanh.restaurant.orders.step
import io.github.phongmanh.restaurant.realtime.application.EventAudience
import io.github.phongmanh.restaurant.realtime.application.EventLog
import io.github.phongmanh.restaurant.realtime.application.EventSubject
import io.github.phongmanh.restaurant.realtime.application.RealtimePublisher
import io.github.phongmanh.restaurant.realtime.domain.Channels
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.FakeAbly
import io.github.phongmanh.restaurant.support.Guests
import io.github.phongmanh.restaurant.support.MutableClock
import io.github.phongmanh.restaurant.support.NoLimits
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.TestKeys
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.github.phongmanh.restaurant.support.testApp
import io.github.phongmanh.restaurant.tables.job
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import org.koin.ktor.ext.get
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** IT-RT-01, IT-RT-07, IT-RT-08, IT-RT-12, IT-RT-13 and IT-RT-14 (realtime §3, §4; ADR-0003; CR-013; NFR-REL-03, NFR-PRIV-01). */
class PublishingTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    @Test
    fun `IT-RT-01 events are written with the change and published after its commit, and a rollback writes neither`() {
        val ably = FakeAbly()
        val restaurant = AuthFixtures.restaurant()
        val visibleWhenPublished = mutableListOf<Int>()
        ably.onPublish = { n ->
            visibleWhenPublished += AuthFixtures.query("SELECT count(*) FROM domain_events WHERE id = ?", n.id) { it.getInt(1) }.single()
        }
        testApp(
            overrides = listOf(module { single<RealtimePublisher> { ably } }),
            routes = { app ->
                // Two events in one transaction, then a commit or a rollback.
                get("/test/realtime/{outcome}") {
                    val log = app.get<EventLog>()
                    val committed =
                        runCatching {
                            app.get<Transactor>().inTransaction(restaurant) {
                                log.append(this, restaurant, "BillClosed", EventSubject("bill", UuidV7.next(), 1), EventAudience.STAFF)
                                log.append(this, restaurant, "BillAdjusted", EventSubject("bill", UuidV7.next(), 2), EventAudience.STAFF)
                                check(call.parameters["outcome"] == "commit")
                            }
                        }.isSuccess
                    call.respondText(committed.toString())
                }
            },
        ) {
            assertEquals("false", client.get("/test/realtime/rollback").bodyAsText())
            assertEquals(listOf(0, 0), listOf(eventIds(restaurant).size, outboxRows(restaurant)), "a rollback writes neither")
            assertEquals(0, ably.tries(), "nor publishes")

            assertEquals("true", client.get("/test/realtime/commit").bodyAsText())
            assertEquals(listOf(2, 2), listOf(eventIds(restaurant).size, outboxRows(restaurant)))
            assertEquals(eventIds(restaurant), ably.received().map { it.id.toString() }, "both, in seq order")
            assertEquals(listOf(1, 1), visibleWhenPublished, "each was committed before it was published")
            assertEquals(0, unsent(restaurant))
        }
    }

    @Test
    fun `IT-RT-07 with Ably down requests still succeed, and later requests and the sweep publish each event once`() {
        val ably = FakeAbly()
        val clock = MutableClock()
        realtimeApp(ably, clock) {
            val f = client.floor()
            ably.down = true
            val first = client.placed(f.guest, line(f.tea)).str("id")
            val second = client.placed(f.guest, line(f.tea)).str("id")
            assertEquals(2, unsent(f.id), "the orders went through; their events wait")

            ably.down = false
            client.step(f.kitchen, first, "accepted", 0)
            assertEquals(2, unsent(f.id), "its own event goes at once; the others aren't 10 s overdue yet")
            clock.advance(11.seconds)
            client.step(f.kitchen, second, "accepted", 0)
            assertEquals(0, unsent(f.id), "a later request of the restaurant publishes the overdue ones")

            // A restaurant that goes quiet: only the sweep is left.
            val quiet = client.floor("B")
            ably.down = true
            client.placed(quiet.guest, line(quiet.tea))
            ably.down = false
            assertEquals(1, unsent(quiet.id))
            val run = client.job(Endpoints.Jobs.OUTBOX_SWEEP, TestKeys.CRON_SECRET).json()
            assertEquals(listOf(1, 0, 0), listOf("published", "failed", "pending").map { run.getValue(it).jsonPrimitive.int })
            assertEquals(0, unsent(quiet.id))

            val received = ably.received().map { it.id.toString() }
            assertEquals(received.distinct(), received, "no event published twice")
            assertEquals((eventIds(f.id) + eventIds(quiet.id)).toSet(), received.toSet(), "and none missing")
            assertEquals("unauthenticated", client.job(Endpoints.Jobs.OUTBOX_SWEEP, "wrong").problem())
        }
    }

    @Test
    fun `IT-RT-07 after 5 failures in a row requests stop trying for 30 s`() {
        val ably = FakeAbly()
        val clock = MutableClock()
        realtimeApp(ably, clock) {
            val f = client.floor()
            val before = ably.tries()
            ably.down = true
            repeat(7) { client.placed(f.guest, line(f.tea)) }
            assertEquals(5, ably.tries() - before, "the breaker opened after the fifth")
            ably.down = false
            client.placed(f.guest, line(f.tea))
            assertEquals(5, ably.tries() - before, "still open")
            clock.advance(31.seconds)
            client.placed(f.guest, line(f.tea))
            assertEquals(0, unsent(f.id), "closed again: its own event and the 8 overdue ones")
        }
    }

    @Test
    fun `IT-RT-08 Ably's rate limit gets one retry, and two sweepers never claim the same row`() {
        val ably = FakeAbly()
        val clock = MutableClock()
        realtimeApp(ably, clock) {
            val f = client.floor()
            ably.rateLimitNext = 1
            client.placed(f.guest, line(f.tea))
            val id = UUID.fromString(eventIds(f.id).last())
            assertEquals(2, ably.triesOf(id), "refused once with 42918, then sent")
            assertEquals(0, unsent(f.id))

            ably.down = true
            val seats = List(3) { Guests.seated(f.id) }
            repeat(30) { client.placed(seats[it % 3].token, line(f.tea)) }
            ably.down = false
            assertEquals(30, unsent(f.id))

            // Another claimer holds 10 rows: the sweeps skip them (SKIP LOCKED) instead of waiting.
            TestDatabase.connect(TestDatabase.platform).use { other ->
                val held =
                    other
                        .prepareStatement(
                            "SELECT event_id FROM outbox WHERE published_at IS NULL ORDER BY next_attempt_at LIMIT 10 FOR UPDATE",
                        ).executeQuery()
                        .use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                val runs =
                    coroutineScope {
                        List(2) { async { client.job(Endpoints.Jobs.OUTBOX_SWEEP, TestKeys.CRON_SECRET).json() } }.awaitAll()
                    }
                assertEquals(20, runs.sumOf { it.getValue("published").jsonPrimitive.int })
                assertTrue(ably.received().none { it.id.toString() in held })
                other.rollback()
            }
            assertEquals(
                10,
                client
                    .job(Endpoints.Jobs.OUTBOX_SWEEP, TestKeys.CRON_SECRET)
                    .json()
                    .getValue("published")
                    .jsonPrimitive.int,
            )
            val received = ably.received().map { it.id }
            assertEquals(received.distinct(), received, "each event published once")
            assertEquals(0, unsent(f.id))
        }
    }

    @Test
    fun `IT-RT-12 the purge deletes events older than 7 days with their outbox rows, and older cursors reset`() {
        val clock = MutableClock()
        realtimeApp(clock = clock) {
            val f = client.floor()
            val latest = client.catchUp(staffEvents, f.waiter).cursor()
            AuthFixtures.sql(
                "UPDATE domain_events SET occurred_at = ? WHERE restaurant_id = ? AND seq <= 3",
                clock.instant().minusSeconds(7.days.inWholeSeconds + 1).atOffset(ZoneOffset.UTC),
                f.id.value,
            )
            AuthFixtures.sql("UPDATE outbox SET published_at = NULL WHERE restaurant_id = ?", f.id.value)
            val run = client.job(Endpoints.Jobs.EVENT_PURGE, TestKeys.CRON_SECRET).json()
            assertEquals(3, run.getValue("deleted").jsonPrimitive.int)
            assertEquals(latest - 3, eventIds(f.id).size.toLong())
            assertEquals(latest - 3, outboxRows(f.id).toLong(), "their outbox rows, sent or not, went with them")
            assertEquals(
                "true",
                client
                    .catchUp(staffEvents, f.waiter, after = 2)
                    .getValue("reset")
                    .jsonPrimitive.content,
            )
            assertEquals((4..latest).toList(), client.catchUp(staffEvents, f.waiter, after = 3).seqs())
            assertEquals(
                0,
                client
                    .job(Endpoints.Jobs.EVENT_PURGE, TestKeys.CRON_SECRET)
                    .json()
                    .getValue("deleted")
                    .jsonPrimitive.int,
            )
            assertEquals("unauthenticated", client.job(Endpoints.Jobs.EVENT_PURGE, null).problem())
        }
    }

    @Test
    fun `IT-RT-13 nothing personal in events or Ably messages`() {
        val ably = FakeAbly()
        realtimeApp(ably) {
            val f = client.floor()
            val note = "Anh Minh 0901234567"
            val order = client.placed(f.guest, line(f.tea, note = note)).str("id")
            val placed = ably.received().last()
            assertEquals(listOf(Channels.staff(f.id), Channels.session(f.seat.session)), placed.channels)
            assertEquals(
                mapOf(
                    "seq" to eventIds(f.id).size.toLong(),
                    "type" to "OrderPlaced",
                    "id" to order,
                    "version" to 0,
                    "session_id" to f.session,
                ),
                placed.data,
            )
            assertTrue(ably.received().none { note in it.toString() || "0901234567" in it.toString() })
            val columns =
                AuthFixtures.query(
                    "SELECT column_name FROM information_schema.columns " +
                        "WHERE table_name IN ('domain_events', 'outbox') ORDER BY column_name",
                ) { it.getString(1) }
            assertEquals(
                listOf(
                    "aggregate_id",
                    "aggregate_type",
                    "aggregate_version",
                    "attempts",
                    "audience",
                    "created_at",
                    "event_id",
                    "id",
                    "next_attempt_at",
                    "occurred_at",
                    "published_at",
                    "restaurant_id",
                    "restaurant_id",
                    "seq",
                    "session_id",
                    "type",
                ),
                columns,
                "IDs, types, versions and times only",
            )
        }
    }

    @Test
    fun `IT-RT-14 with live push off an event is logged with no outbox row, nothing is published, and catch-up returns it`() {
        val ably = FakeAbly()
        val tenant = Tenants.create("A", "manager@a.example")
        val order = UuidV7.next()
        testApp(
            config = TestDatabase.config.copy(realtime = RealtimeSettings(null, LivePush.OFF)),
            overrides = listOf(module { single<RealtimePublisher> { ably } }, module { single<RateLimiter> { NoLimits } }),
            routes = { app ->
                get("/test/realtime/append") {
                    app.get<Transactor>().inTransaction(tenant.id) {
                        app.get<EventLog>().append(this, tenant.id, "OrderPlaced", EventSubject("order", order, 1), EventAudience.STAFF)
                    }
                    call.respondText("ok")
                }
            },
        ) {
            client.get("/test/realtime/append")
            assertEquals(listOf(1, 0), listOf(eventIds(tenant.id).size, outboxRows(tenant.id)), "the event, and no outbox row")
            assertEquals(0, ably.tries(), "nothing is published")
            assertEquals(listOf("OrderPlaced:$order:1"), client.catchUp(staffEvents, tenant.managerToken, after = 0).eventList())
        }
    }

    private fun outboxRows(restaurant: RestaurantId): Int =
        AuthFixtures.query("SELECT count(*) FROM outbox WHERE restaurant_id = ?", restaurant.value) { it.getInt(1) }.single()
}
