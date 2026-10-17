package io.github.phongmanh.restaurant.orders

import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.obj
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.github.phongmanh.restaurant.tables.auditOf
import io.github.phongmanh.restaurant.tables.count
import io.github.phongmanh.restaurant.tables.eventsOf
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** IT-ORD-07 to IT-ORD-11 (orders §2, §5; FR-006, FR-012, FR-015). */
class OrderStatusTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val e = Endpoints.Orders

    @Test
    fun `IT-ORD-07 a guest cancels a placed order, twice with one result, and not an accepted one`() =
        ordersApp {
            val f = client.floor()
            val order = client.placed(f.guest, line(f.tea))
            val keep = client.placed(f.guest, line(f.pho, options = listOf(f.small)))
            val path = "orderId" to order.str("id")
            val cancelled = client.api(e.CANCEL_GUEST_ORDER, f.guest, path)
            assertEquals(HttpStatusCode.OK, cancelled.status)
            assertEquals(listOf("cancelled", "1"), cancelled.json().let { listOf(it.str("status"), it.str("version")) })
            val again = client.api(e.CANCEL_GUEST_ORDER, f.guest, path)
            assertEquals(HttpStatusCode.OK, again.status)
            assertEquals(cancelled.json(), again.json(), "a retry gives the same result")
            val bill = client.api(e.GET_GUEST_BILL, f.guest).json()
            assertEquals(listOf(65_000L, 1L), listOf(bill.money("total"), bill.str("order_count").toLong()), "off the bill")

            client.step(f.kitchen, keep.str("id"), "accepted", 0)
            val late = client.api(e.CANCEL_GUEST_ORDER, f.guest, "orderId" to keep.str("id"))
            assertEquals("order_not_cancellable", late.problem())
            assertEquals(listOf("order.status_changed"), auditOf(f.id, "order."), "only the kitchen's step")
        }

    @Test
    fun `IT-ORD-08 cancel and accept at the same moment - exactly one wins`() =
        ordersApp {
            val f = client.floor()
            repeat(5) {
                val id = client.placed(f.guest, line(f.tea)).str("id")
                val (cancel, accept) =
                    coroutineScope {
                        listOf(
                            async { client.api(e.CANCEL_GUEST_ORDER, f.guest, "orderId" to id) },
                            async { client.step(f.kitchen, id, "accepted", 0) },
                        ).awaitAll()
                    }
                val wins = listOf(cancel, accept).count { it.status == HttpStatusCode.OK }
                assertEquals(1, wins, "round $it")
                val final = client.api(e.GET_STAFF_ORDER, f.waiter, "orderId" to id).json().str("status")
                if (cancel.status == HttpStatusCode.OK) {
                    assertEquals("cancelled", final)
                    assertEquals("precondition_failed", accept.problem())
                } else {
                    assertEquals("accepted", final)
                    assertEquals("order_not_cancellable", cancel.problem())
                }
            }
        }

    /** A new order moved to [status] by the manager; returns its ID and version. */
    private suspend fun HttpClient.orderAt(
        f: Floor,
        status: String,
    ): Pair<String, Int> {
        val id = placed(f.guest, line(f.tea)).str("id")
        if (status == "cancelled") {
            api(e.CANCEL_GUEST_ORDER, f.guest, "orderId" to id)
            return id to 1
        }
        PATH.drop(1).take(PATH.indexOf(status)).forEachIndexed { v, s -> assertEquals(200, step(f.manager, id, s, v).status.value) }
        return id to PATH.indexOf(status)
    }

    private fun reasonFor(to: String) = if (to == "rejected" || to == "cancelled") """{"code": "kitchen_closed"}""" else null

    @Test
    fun `IT-ORD-09 each step is made by its roles only, and the others get 403`() =
        ordersApp {
            val f = client.floor()
            val roles = mapOf("kitchen" to f.kitchen, "waiter" to f.waiter, "manager" to f.manager)
            for ((to, step) in STEPS) {
                for ((role, token) in roles) {
                    val (id, version) = client.orderAt(f, step.first)
                    val response = client.step(token, id, to, version, reasonFor(to))
                    if (role in step.second) {
                        assertEquals(HttpStatusCode.OK, response.status, "$role → $to")
                        assertEquals(listOf(to, "${version + 1}"), response.json().let { listOf(it.str("status"), it.str("version")) })
                        assertEquals("\"${version + 1}\"", response.headers[HttpHeaders.ETag])
                    } else {
                        assertEquals("forbidden", response.problem(), "$role → $to")
                    }
                }
            }
        }

    @Test
    fun `IT-ORD-09 a step from any other status is 409 invalid_transition`() =
        ordersApp {
            val f = client.floor()
            for ((to, step) in STEPS) {
                for (start in PATH + "cancelled") {
                    if (start == step.first) continue
                    val (id, version) = client.orderAt(f, start)
                    assertEquals("invalid_transition", client.step(f.manager, id, to, version, reasonFor(to)).problem(), "$start → $to")
                }
            }
        }

    @Test
    fun `IT-ORD-09 a stale or missing If-Match, and a body that breaks the rules`() =
        ordersApp {
            val f = client.floor()
            val (id, version) = client.orderAt(f, "placed")
            val reason = """{"code": "kitchen_closed"}"""
            assertEquals("precondition_failed", client.step(f.kitchen, id, "accepted", version + 1).problem())
            val noIfMatch = client.api(e.CHANGE_ORDER_STATUS, f.kitchen, "orderId" to id, body = """{"status": "accepted"}""")
            assertEquals("precondition_required", noIfMatch.problem())
            assertEquals("validation_failed", client.step(f.kitchen, id, "placed", version).problem(), "placed isn't a step")
            assertEquals("validation_failed", client.step(f.manager, id, "rejected", version).problem(), "a rejection needs a reason")
            assertEquals(
                "validation_failed",
                client.step(f.manager, id, "accepted", version, reason).problem(),
                "a reason only with reject or cancel",
            )
            val closing = client.step(f.manager, id, "cancelled", version, """{"code": "closed_with_session"}""")
            assertEquals("validation_failed", closing.problem(), "set by the close only")
        }

    @Test
    fun `IT-ORD-10 every staff step writes its audit entry with the actor, and its event with an outbox row`() =
        ordersApp {
            val f = client.floor()
            val id = client.placed(f.guest, line(f.tea)).str("id")
            client.step(f.kitchen, id, "accepted", 0)
            client.step(f.kitchen, id, "preparing", 1)
            client.step(f.kitchen, id, "ready", 2)
            client.step(f.waiter, id, "served", 3)
            val steps =
                AuthFixtures.query(
                    "SELECT actor_type, actor_id, details->>'from', details->>'to' FROM audit_events " +
                        "WHERE restaurant_id = ? AND action = 'order.status_changed' ORDER BY chain_seq",
                    f.id.value,
                ) { "${it.getString(1)}:${it.getString(2) != null}:${it.getString(3)}→${it.getString(4)}" }
            assertEquals(
                listOf(
                    "staff:true:placed→accepted",
                    "staff:true:accepted→preparing",
                    "staff:true:preparing→ready",
                    "staff:true:ready→served",
                ),
                steps,
            )
            assertEquals(List(4) { "OrderStatusChanged" }, eventsOf(f.id).filter { it == "OrderStatusChanged" })
            assertEquals(
                5,
                count(
                    "SELECT count(*) FROM outbox o JOIN domain_events d ON d.id = o.event_id " +
                        "WHERE d.restaurant_id = ? AND d.type LIKE 'Order%'",
                    f.id.value,
                ),
            )
        }

    @Test
    fun `IT-ORD-11 a rejected line leaves the bill, the last one rejects the order, and only accepted or preparing orders`() =
        ordersApp {
            val f = client.floor()
            val order = client.placed(f.guest, line(f.pho, options = listOf(f.large)), line(f.tea, 2))
            val id = order.str("id")
            val (pho, tea) = order.getValue("lines").jsonArray.map { it.jsonObject.str("id") }
            val reason = """{"reason": {"code": "item_unavailable", "note": "hết bò"}}"""

            suspend fun reject(
                token: String,
                line: String,
                version: Int,
            ) = client.api(e.REJECT_ORDER_LINE, token, "orderId" to id, "lineId" to line, body = reason) {
                header(HttpHeaders.IfMatch, "\"$version\"")
            }

            assertEquals("line_not_rejectable", reject(f.kitchen, pho, 0).problem(), "a placed order is rejected whole")
            client.step(f.kitchen, id, "accepted", 0)
            assertEquals("forbidden", reject(f.waiter, pho, 1).problem())
            for (body in listOf("""{}""", """{"reason": {"code": "closed_with_session"}}""")) {
                val bad =
                    client.api(e.REJECT_ORDER_LINE, f.kitchen, "orderId" to id, "lineId" to pho, body = body) {
                        header(HttpHeaders.IfMatch, "\"1\"")
                    }
                assertEquals(HttpStatusCode.BadRequest, bad.status, body)
                assertEquals("validation_failed", bad.problem(), body)
            }
            val first = reject(f.kitchen, pho, 1)
            assertEquals(HttpStatusCode.OK, first.status)
            val after = first.json()
            assertEquals(listOf("accepted", "10000"), listOf(after.str("status"), after.obj("total").str("amount")))
            val rejected = after.getValue("lines").jsonArray[0].jsonObject
            assertEquals(
                listOf("rejected", "item_unavailable", "hết bò"),
                listOf(rejected.str("status"), rejected.obj("reason").str("code"), rejected.obj("reason").str("note")),
            )
            val guestLine =
                client
                    .api(e.GET_GUEST_ORDER, f.guest, "orderId" to id)
                    .json()
                    .getValue("lines")
                    .jsonArray[0]
                    .jsonObject
            assertNull(guestLine.obj("reason")["note"], "the note is staff-only")
            val bill = client.api(e.GET_GUEST_BILL, f.guest).json()
            assertEquals(10_000, bill.money("total"))
            assertEquals(listOf(tea), bill.getValue("lines").jsonArray.map { it.jsonObject.str("line_id") })
            assertEquals("line_not_rejectable", reject(f.kitchen, pho, 2).problem(), "already rejected")
            assertEquals("precondition_failed", reject(f.kitchen, tea, 1).problem())

            val last = reject(f.manager, tea, 2).json()
            assertEquals(
                listOf("rejected", "item_unavailable", "0"),
                listOf(last.str("status"), last.obj("reason").str("code"), last.obj("total").str("amount")),
            )
            val empty = client.bill(f.waiter, f.session)
            assertEquals(listOf(0L, 0L), listOf(empty.money("total"), empty.str("order_count").toLong()))
            assertEquals(listOf("order.status_changed", "order.line_rejected", "order.line_rejected"), auditOf(f.id, "order."))
            assertEquals(
                listOf("OrderPlaced", "OrderStatusChanged", "OrderLineRejected", "OrderLineRejected", "OrderStatusChanged"),
                eventsOf(f.id).filter { it.startsWith("Order") },
            )
        }

    @Test
    fun `IT-ORD-11 a ready order's line can't be rejected - the food is made`() =
        ordersApp {
            val f = client.floor()
            val reason = """{"reason": {"code": "item_unavailable"}}"""
            val served = client.placed(f.guest, line(f.tea)).str("id")
            listOf("accepted", "preparing", "ready").forEachIndexed { v, s -> client.step(f.kitchen, served, s, v) }
            val readyLine =
                client
                    .api(
                        e.GET_STAFF_ORDER,
                        f.kitchen,
                        "orderId" to served,
                    ).json()
                    .getValue("lines")
                    .jsonArray[0]
                    .jsonObject
                    .str("id")
            val tooLate =
                client.api(e.REJECT_ORDER_LINE, f.kitchen, "orderId" to served, "lineId" to readyLine, body = reason) {
                    header(HttpHeaders.IfMatch, "\"3\"")
                }
            assertEquals("line_not_rejectable", tooLate.problem(), "after ready the food is made")
        }

    private companion object {
        val PATH = listOf("placed", "accepted", "preparing", "ready", "served")

        /** Each step (orders §2): the status it's made from, and the roles that may make it. */
        val STEPS =
            mapOf(
                "accepted" to ("placed" to setOf("kitchen", "manager")),
                "preparing" to ("accepted" to setOf("kitchen", "manager")),
                "ready" to ("preparing" to setOf("kitchen", "manager")),
                "served" to ("ready" to setOf("kitchen", "waiter", "manager")),
                "rejected" to ("placed" to setOf("kitchen", "waiter", "manager")),
                "cancelled" to ("placed" to setOf("waiter", "manager")),
            )
    }
}
