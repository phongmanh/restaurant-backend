package io.github.phongmanh.restaurant.realtime

import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.orders.floor
import io.github.phongmanh.restaurant.orders.line
import io.github.phongmanh.restaurant.orders.placed
import io.github.phongmanh.restaurant.orders.step
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Guests
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.errorFields
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** IT-RT-02 to IT-RT-06 (realtime §2, §5, §6; FR-005, FR-011, NFR-REL-03, NFR-SEC-02). */
class CatchUpTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    @Test
    fun `IT-RT-03 events after the cursor, oldest first, with one snapshot per order at its latest version`() =
        realtimeApp {
            val f = client.floor()
            val start = client.catchUp(guestEvents, f.guest)
            assertEquals(emptyList(), start.eventList(), "without after: only the cursor")
            assertEquals(start.cursor(), client.catchUp(staffEvents, f.kitchen).cursor(), "one sequence per restaurant")

            val order = client.placed(f.guest, line(f.tea)).str("id")
            client.step(f.kitchen, order, "accepted", 0)
            val page = client.catchUp(guestEvents, f.guest, after = start.cursor())
            assertEquals(listOf("OrderPlaced:$order:0", "OrderStatusChanged:$order:1"), page.eventList())
            assertEquals(listOf(start.cursor() + 1, start.cursor() + 2), page.seqs())
            val snapshot = page.snapshots("orders").single()
            assertEquals(listOf(order, "accepted", "1"), listOf(snapshot.str("id"), snapshot.str("status"), snapshot.str("version")))
            assertEquals(emptyList(), page.snapshots("waiter_calls"))
            assertEquals(start.cursor() + 2, page.cursor())
            assertFalse(
                page
                    .getValue("has_more")
                    .jsonPrimitive.content
                    .toBoolean(),
            )
            assertNull(page["reset"], "only sent when true")

            val quiet = client.catchUp(guestEvents, f.guest, after = page.cursor())
            assertEquals(
                listOf<Any>(emptyList<String>(), page.cursor()),
                listOf(quiet.eventList(), quiet.cursor()),
                "nothing new: same cursor",
            )

            // Three more events, read two at a time.
            repeat(3) { client.placed(f.guest, line(f.tea)) }
            val first = client.catchUp(staffEvents, f.waiter, after = page.cursor(), limit = 2)
            assertEquals(listOf(page.cursor() + 1, page.cursor() + 2), first.seqs())
            assertEquals(page.cursor() + 2, first.cursor())
            assertTrue(
                first
                    .getValue("has_more")
                    .jsonPrimitive.content
                    .toBoolean(),
            )
            val second = client.catchUp(staffEvents, f.waiter, after = first.cursor(), limit = 2)
            assertEquals(listOf(page.cursor() + 3), second.seqs())
            assertFalse(
                second
                    .getValue("has_more")
                    .jsonPrimitive.content
                    .toBoolean(),
            )
            assertEquals(3, (first.snapshots("orders") + second.snapshots("orders")).size)
        }

    @Test
    fun `IT-RT-04 a guest reads only their session's events, never staff-only ones`() =
        realtimeApp {
            val f = client.floor()
            val other = Guests.seated(f.id)
            val start = client.catchUp(guestEvents, f.guest).cursor()

            val mine = client.placed(f.guest, line(f.tea)).str("id")
            val theirs = client.placed(other.token, line(f.tea)).str("id")
            val call = client.api(Endpoints.Tables.CALL_WAITER, f.guest, body = """{"kind": "call_waiter"}""").json().str("id")
            client.api(Endpoints.Tables.ACKNOWLEDGE_WAITER_CALL, f.waiter, "callId" to call)
            client.api(Endpoints.Menu.SET_MENU_ITEM_SOLD_OUT, f.manager, "itemId" to f.tea, body = """{"sold_out": true}""")

            val guest = client.catchUp(guestEvents, f.guest, after = start)
            assertEquals(listOf("OrderPlaced:$mine", "WaiterCallAcknowledged:$call"), guest.eventList().map { it.substringBeforeLast(':') })
            assertEquals(listOf(mine), guest.snapshots("orders").map { it.str("id") })
            assertEquals(listOf(call to "acknowledged"), guest.snapshots("waiter_calls").map { it.str("id") to it.str("status") })
            val latest = client.catchUp(staffEvents, f.waiter).cursor()
            assertEquals(latest, guest.cursor(), "the cursor skips the other session's and the staff-only events")

            val staff = client.catchUp(staffEvents, f.waiter, after = start)
            assertEquals(
                listOf("OrderPlaced", "OrderPlaced", "WaiterCalled", "WaiterCallAcknowledged", "MenuChanged"),
                staff.eventList().map { it.substringBefore(':') },
            )
            assertEquals(setOf(mine, theirs), staff.snapshots("orders").map { it.str("id") }.toSet())
            assertEquals(listOf(call), staff.snapshots("waiter_calls").map { it.str("id") }.distinct())
        }

    @Test
    fun `IT-RT-05 another restaurant's events never appear`() =
        realtimeApp {
            val a = client.floor("A")
            val b = client.floor("B")
            val order = client.placed(a.guest, line(a.tea)).str("id")
            val page = client.catchUp(staffEvents, b.waiter, after = 0)
            assertTrue(page.eventList().none { order in it })
            assertEquals(emptyList(), page.snapshots("orders"))
            assertEquals(listOf("MenuChanged"), page.eventList().map { it.substringBefore(':') }.distinct(), "B's own menu writes only")
        }

    @Test
    fun `IT-RT-06 a cursor ahead of the log, or older than the oldest kept event, is reset`() =
        realtimeApp {
            val f = client.floor()
            val latest = client.catchUp(staffEvents, f.waiter).cursor()
            val ahead = client.catchUp(staffEvents, f.waiter, after = latest + 5)
            assertEquals("true", ahead.getValue("reset").jsonPrimitive.content)
            assertEquals(listOf<Any>(emptyList<String>(), latest), listOf(ahead.eventList(), ahead.cursor()))

            AuthFixtures.sql("DELETE FROM domain_events WHERE restaurant_id = ? AND seq <= 2", f.id.value)
            val old = client.catchUp(staffEvents, f.waiter, after = 1)
            assertEquals(listOf("true", "$latest"), listOf(old.getValue("reset").jsonPrimitive.content, "${old.cursor()}"))
            val kept = client.catchUp(staffEvents, f.waiter, after = 2)
            assertNull(kept["reset"], "everything after 2 is still there")
            assertEquals((3..latest).toList(), kept.seqs())
        }

    @Test
    fun `IT-RT-02 concurrent writes take gap-free numbers, and a reader never sees one before the one below it`() =
        realtimeApp {
            val f = client.floor()
            val seats = listOf(f.seat) + List(4) { Guests.seated(f.id) }
            val start = client.catchUp(staffEvents, f.waiter).cursor()
            val seen = Collections.synchronizedList(mutableListOf<Long>())
            val writing = AtomicBoolean(true)
            coroutineScope {
                val reader =
                    launch {
                        var cursor = start
                        // One more read after the writes end picks up the last of them.
                        var last = false
                        while (!last) {
                            last = !writing.get()
                            val page = client.catchUp(staffEvents, f.waiter, after = cursor, limit = 200)
                            val seqs = page.seqs()
                            assertEquals(((cursor + 1)..(cursor + seqs.size)).toList(), seqs, "contiguous after $cursor")
                            seen += seqs
                            cursor = page.cursor()
                        }
                    }
                (1..WRITES)
                    .map { i -> async { client.placed(seats[i % seats.size].token, line(f.tea)) } }
                    .awaitAll()
                writing.set(false)
                reader.join()
            }
            val all = client.catchUp(staffEvents, f.waiter, after = start, limit = 200).seqs()
            assertEquals(WRITES, all.size, "one event per order")
            assertEquals(((start + 1)..(start + WRITES)).toList(), all, "no gaps or repeats")
            assertEquals(all, seen.toList())
        }

    @Test
    fun `a bad after or limit is 400, and suspension stops staff but not guests`() =
        realtimeApp {
            val f = client.floor()
            listOf("after=-1" to "query.after", "after=x" to "query.after", "limit=0" to "query.limit", "limit=201" to "query.limit")
                .forEach { (query, field) ->
                    for ((events, token) in listOf(staffEvents to f.waiter, guestEvents to f.guest)) {
                        val response = client.api(events.method, events.path + "?" + query, token)
                        assertEquals(HttpStatusCode.BadRequest, response.status, "$events $query")
                        assertEquals(listOf(field), response.errorFields(), "$events $query")
                    }
                }
            AuthFixtures.suspend(f.id)
            assertEquals(HttpStatusCode.OK, client.api(guestEvents, f.guest).status, "guests keep reading (restaurants §6)")
            assertEquals("restaurant_suspended", client.api(staffEvents, f.waiter).problem())
        }

    private companion object {
        const val WRITES = 50
    }
}
