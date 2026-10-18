package io.github.phongmanh.restaurant.system

import io.github.phongmanh.restaurant.staging.StagingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** test-plan §5, the staff's side of an order: AT-011 to AT-013 (FR-011 to FR-013). */
class KitchenAndBillTest {
    private val a = Staging.a
    private val b = Staging.b
    private val api = Staging.api

    @Test
    fun `AT-011 the kitchen sees exactly its own orders within NFR-PERF-03, and misses none after a network cut`() =
        a.withTables(5) { tables ->
            val start = api.expect(OK, "GET", "/v1/staff/events", token = a.kitchen).json.str("cursor")
            val item = a.item("Grilled pork with noodles").str("id")
            Live.staff(a, a.kitchen).connect().use { screen ->
                val placed =
                    tables.take(3).mapIndexed { i, t ->
                        a.seat(t.str("id")).order(line(item, 1, "bàn ${t.str("number")}, phần $i")) to
                            Instant.now()
                    }
                val bOrder = b.withTables(1) { (t) -> b.seat(t.str("id")).order(line(b.item("Iced tea").str("id"))) }
                for ((order, at) in placed) {
                    val note = screen.await { it.type == "OrderPlaced" && it.id == order.str("id") }
                    assertNotNull(note, "the kitchen screen got order ${order.str("id")}")
                    val delay = Live.delay(at, note)
                    println("[AT-011] order at table ${order.obj("table").str("number")} reached the kitchen in ${delay.toMillis()} ms")
                    assertTrue(delay <= screen.limit, "within ${screen.limit}: took $delay")
                }
                assertTrue(screen.all().none { it.id == bOrder.str("id") }, "nothing of restaurant B on A's channel")
                val screenList = api.expect(OK, "GET", "/v1/staff/orders?status=placed&limit=100", token = a.kitchen).arrOf("items")
                for ((order, _) in placed) {
                    val shown = screenList.single { it.str("id") == order.str("id") }
                    assertEquals(order.obj("table").str("number"), shown.obj("table").str("number"), "with its table number")
                    assertEquals(order.arrOf("lines").single().str("note"), shown.arrOf("lines").single().str("note"), "with its note")
                    assertEquals(item, shown.arrOf("lines").single().str("item_id"), "with its items")
                }
                assertTrue(screenList.none { it.str("id") == bOrder.str("id") }, "B's order isn't on A's list")

                screen.disconnect()
                val whileCut = tables.drop(3).map { a.seat(it.str("id")).order(line(item)) }
                assertNoGaps(start, placed.map { it.first } + whileCut)
            }
        }

    @Test
    fun `AT-012 each step shows to guests and staff, a double mark is told to refresh, a skipped stage is refused`() =
        a.withTables(1) { (table) ->
            val guest = a.seat(table.str("id"))
            var order = guest.order(line(a.item("Broken rice with pork").str("id")))
            for (status in listOf("accepted", "preparing", "ready")) {
                order = a.step(order, status)
                assertEquals(
                    status,
                    guest.expect(OK, "GET", "/v1/guest/orders/${order.str("id")}").json.str("status"),
                    "the guest sees $status",
                )
                assertEquals(status, api.expect(OK, "GET", "/v1/staff/orders/${order.str("id")}", token = a.waiter).json.str("status"))
            }
            val (first, second) = markServedTogether(order)
            assertEquals(setOf(OK, PRECONDITION_FAILED), setOf(first.status, second.status), "one succeeds, the other is told to refresh")
            assertEquals("precondition_failed", listOf(first, second).single { it.status != OK }.code)
            assertEquals("served", guest.expect(OK, "GET", "/v1/guest/orders/${order.str("id")}").json.str("status"))

            val fresh = guest.order(line(a.item("Broken rice with pork").str("id")))
            val skip = statusCall(fresh, "served", a.waiter)
            assertEquals(CONFLICT to "invalid_transition", skip.status to skip.code, "placed → served skips the kitchen")
            val kitchenSkip = statusCall(fresh, "preparing", a.kitchen)
            assertEquals(CONFLICT to "invalid_transition", kitchenSkip.status to kitchenSkip.code, "placed → preparing skips accepted")
        }

    @Test
    fun `AT-013 a cash close makes the bill final, the guests can't order, and the next scan opens a new session`() =
        a.withTables(1) { (table) ->
            val code = a.qrToken(table.str("id"))
            val guest1 = Guest.scan(code)
            val guest2 = Guest.scan(code)
            var order = guest1.order(line(a.item("Fresh spring rolls").str("id"), 2), line(a.item("Iced tea").str("id"), 2))
            for (status in listOf("accepted", "preparing", "ready")) order = a.step(order, status)
            a.step(order, "served", a.waiter)

            val closed = a.closeSession(guest1.sessionId, "cash", CASH_RECEIVED)
            assertEquals("closed", closed.str("status"))
            assertEquals("cash", closed.obj("payment").str("method"))
            assertEquals(CASH_RECEIVED - closed.money("total"), closed.obj("payment").money("change"), "the change is right")
            val again =
                api.call(
                    "POST",
                    "/v1/staff/table-sessions/${guest1.sessionId}/orders",
                    orderBody(line(a.item("Iced tea").str("id"))),
                    a.waiter,
                    idempotencyKey(),
                )
            assertEquals(CONFLICT to "session_not_open", again.status to again.code, "the bill is final")

            for ((name, guest) in listOf("guest 1" to guest1, "guest 2" to guest2)) {
                val refused = guest.call("POST", "/v1/guest/orders", orderBody(line(a.item("Iced tea").str("id"))), idempotencyKey())
                assertEquals(UNAUTHORIZED to "table_session_closed", refused.status to refused.code, "$name can no longer order")
            }
            val next = Guest.scan(code)
            assertNotEquals(guest1.sessionId, next.sessionId, "the next scan opens a new session")
            assertEquals(0, next.bill().int("order_count"), "and it is empty")
        }

    private fun statusCall(
        order: JsonObject,
        status: String,
        token: String,
    ) = api.call(
        "POST",
        "/v1/staff/orders/${order.str("id")}/status",
        buildJsonObject { put("status", status) },
        token,
        ifMatch(order.int("version")),
    )

    /** Two waiters press `served` on the same version at the same moment. */
    private fun markServedTogether(order: JsonObject): Pair<StagingApi.Response, StagingApi.Response> {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val go = CountDownLatch(1)
            val calls =
                listOf(a.waiter, a.waiter2).map { token ->
                    pool.submit<StagingApi.Response> {
                        go.await()
                        statusCall(order, "served", token)
                    }
                }
            go.countDown()
            return calls[0].get(RACE_TIMEOUT_S, TimeUnit.SECONDS) to calls[1].get(RACE_TIMEOUT_S, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    /** The screen back online: the catch-up from [start] holds every order once (realtime §6, FR-011). */
    private fun assertNoGaps(
        start: String,
        orders: List<JsonObject>,
    ) {
        val placedEvents = mutableListOf<String>()
        val orderRows = mutableListOf<String>()
        var cursor = start
        do {
            val page = api.expect(OK, "GET", "/v1/staff/events?after=$cursor&limit=100", token = a.kitchen).json
            assertTrue(page["reset"]?.toString() != "true", "no reload needed")
            placedEvents += page.arrOf("events").filter { it.str("type") == "OrderPlaced" }.map { it.obj("aggregate").str("id") }
            orderRows += page.arrOf("orders").map { it.str("id") }
            cursor = page.str("cursor")
        } while (page.bool("has_more"))
        for (order in orders) {
            val id = order.str("id")
            assertEquals(1, placedEvents.count { it == id }, "order $id placed once in the catch-up, not missing or doubled")
            assertTrue(id in orderRows, "order $id's current state is in the catch-up")
        }
    }

    private companion object {
        const val CASH_RECEIVED = 450_000L
        const val RACE_TIMEOUT_S = 60L
    }
}
