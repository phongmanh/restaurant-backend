package io.github.phongmanh.restaurant.tables

import io.github.phongmanh.restaurant.core.application.RateLimiter
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.support.MutableClock
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.items
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.obj
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

/** IT-TBL-11 (tables §4, FR-008). */
class WaiterCallTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val e = Endpoints.Tables

    @Test
    fun `IT-TBL-11 taps within 60 s merge into one call, and an acknowledged call clears for every waiter`() {
        val clock = MutableClock()
        tablesApp(clock = clock) {
            val a = Tenants.create("A", "manager@a.example")
            val (_, waiter) = a.member("waiter@a.example", "waiter")
            val (_, other) = a.member("waiter2@a.example", "waiter")
            val (_, kitchen) = a.member("kitchen@a.example", "kitchen")
            val (guest, _) = client.seat(client.qrToken(a.managerToken, client.table(a.managerToken, "A-5").str("id")))

            suspend fun tap(kind: String) = client.api(e.CALL_WAITER, guest, body = """{"kind": "$kind"}""")

            val first = tap("call_waiter")
            assertEquals(HttpStatusCode.Created, first.status)
            val call = first.json()
            assertEquals(
                listOf("call_waiter", "open", "A-5"),
                listOf(call.str("kind"), call.str("status"), call.obj("table").str("number")),
            )
            val merged = tap("call_waiter")
            assertEquals(HttpStatusCode.OK, merged.status, "a repeat tap")
            assertEquals(call.str("id"), merged.json().str("id"))
            val bill = tap("request_bill")
            assertEquals(HttpStatusCode.Created, bill.status, "another kind is another call")

            val open = client.api(e.LIST_WAITER_CALLS, waiter).items().map { it.jsonObject.str("kind") }
            assertEquals(listOf("call_waiter", "request_bill"), open, "oldest first")
            assertEquals("forbidden", client.api(e.LIST_WAITER_CALLS, kitchen).problem())

            val ack = client.api(e.ACKNOWLEDGE_WAITER_CALL, waiter, "callId" to call.str("id")).json()
            assertEquals("acknowledged", ack.str("status"))
            val again = client.api(e.ACKNOWLEDGE_WAITER_CALL, other, "callId" to call.str("id")).json()
            assertEquals(ack, again, "acknowledging twice gives the same result")
            assertEquals(listOf("request_bill"), client.api(e.LIST_WAITER_CALLS, other).items().map { it.jsonObject.str("kind") })
            assertEquals(
                listOf(call.str("id")),
                client.api(e.LIST_WAITER_CALLS.method, e.LIST_WAITER_CALLS.path + "?status=acknowledged", other).items().map {
                    it.jsonObject.str("id")
                },
            )

            val view =
                client
                    .api(e.GET_GUEST_SESSION, guest)
                    .json()
                    .getValue("open_calls")
                    .jsonArray
                    .map { it.jsonObject }
            assertEquals(listOf("acknowledged", "open"), view.map { it.str("status") }, "the guest sees \"a waiter is coming\"")

            assertEquals(HttpStatusCode.OK, tap("call_waiter").status, "within 60 s of the last one, even acknowledged")
            clock.advance(61.seconds)
            val later = tap("call_waiter")
            assertEquals(HttpStatusCode.Created, later.status, "a minute later it's a new call")
            assertNotEquals(call.str("id"), later.json().str("id"))

            assertEquals(
                listOf("WaiterCalled", "WaiterCalled", "WaiterCallAcknowledged", "WaiterCalled"),
                eventsOf(a.id).filter {
                    "Waiter" in
                        it
                },
            )
            assertEquals(listOf("waiter_call.acknowledged"), auditOf(a.id, "waiter_call."), "one entry: the repeat changed nothing")
        }
    }

    @Test
    fun `IT-TBL-11 bad input is 400, another restaurant's call 404, and the 11th new call in an hour 429, merged taps not counted`() {
        val clock = MutableClock()
        val counts = ConcurrentHashMap<String, Long>()
        // Counts per policy and subject, whatever the window: the test moves the clock across minutes.
        val counting = RateLimiter { key, _ -> counts.merge(key.substringBeforeLast(':'), 1, Long::plus)!! }
        tablesApp(clock = clock, limiter = counting) {
            val a = Tenants.create("A", "manager@a.example")
            val b = Tenants.create("B", "manager@b.example")
            val (guest, _) = client.seat(client.qrToken(a.managerToken, client.table(a.managerToken, "1").str("id")))

            suspend fun tap() = client.api(e.CALL_WAITER, guest, body = """{"kind": "call_waiter"}""")

            assertEquals("validation_failed", client.api(e.CALL_WAITER, guest, body = """{"kind": "dance"}""").problem())
            assertEquals(
                "validation_failed",
                client.api(e.LIST_WAITER_CALLS.method, e.LIST_WAITER_CALLS.path + "?status=closed", a.managerToken).problem(),
            )

            repeat(10) { n ->
                val created = tap()
                assertEquals(HttpStatusCode.Created, created.status, "call ${n + 1}")
                assertEquals(HttpStatusCode.OK, tap().status, "a repeat merges")
                val id = created.json().str("id")
                assertEquals("not_found", client.api(e.ACKNOWLEDGE_WAITER_CALL, b.managerToken, "callId" to id).problem())
                client.api(e.ACKNOWLEDGE_WAITER_CALL, a.managerToken, "callId" to id)
                clock.advance(61.seconds)
            }
            val limited = tap()
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("rate_limited", limited.problem())
            assertEquals(11L, counts.filterKeys { "waiter_call" in it }.values.single(), "ten calls and the refused one; no merged tap")
        }
    }
}
