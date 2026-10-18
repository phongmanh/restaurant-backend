package io.github.phongmanh.restaurant.system

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * test-plan §5 AT-042 (FR-042) and §4 ST-12, the one authorisation matrix on staging (Q-16): restaurant
 * A's staff and guest replay every operation that names an ID with restaurant B's IDs. A path ID of
 * B's must answer `404`, as if it didn't exist (ADR-0005); an ID of B's in a body must be refused.
 * The manager may make every staff operation, so each of theirs must reach the `404`; a waiter's or
 * the kitchen's may stop at `403` first where the role lacks it (core-and-auth §3: 403 before 404),
 * which says nothing about B. B's state is read before and after, and must not change. Reports name no ID: a report only ever
 * reads the caller's restaurant, which CI's RLS tests prove (IT-CORE-06, IT-CORE-07).
 */
class IsolationTest {
    private val a = Staging.a
    private val b = Staging.b
    private val api = Staging.api

    private class Call(
        val method: String,
        val path: String,
        val body: JsonElement? = null,
        val headers: Map<String, String> = emptyMap(),
    ) {
        override fun toString() = "$method $path"
    }

    @Test
    fun `AT-042 every call with restaurant B's IDs answers 404 and changes nothing in B`() =
        b.withTables(2) { (bTable, bOther) ->
            val target = BTarget.make(bTable, bOther)
            val before = snapshot(target)
            val wrong = mutableListOf<String>()
            for ((role, token) in listOf("manager" to a.manager, "waiter" to a.waiter, "kitchen" to a.kitchen)) {
                for (call in staffCalls(target)) {
                    val response = api.call(call.method, call.path, call.body, token, call.headers)
                    val allowed = if (role == "manager") setOf(NOT_FOUND) else setOf(NOT_FOUND, FORBIDDEN)
                    if (response.status !in allowed) wrong += "A's $role: $call → ${response.status} ${response.code}"
                }
            }
            a.withTables(1) { (aTable) ->
                val guest = a.seat(aTable.str("id"))
                for (call in guestCalls(target)) {
                    val response = guest.call(call.method, call.path, call.body, call.headers)
                    if (response.status != NOT_FOUND) wrong += "A's guest: $call → ${response.status} ${response.code}"
                }
                for ((call, token) in bodyCalls(target, guest)) {
                    val response = api.call(call.method, call.path, call.body, token, call.headers)
                    println("[AT-042] B's ID in the body: $call → ${response.status} ${response.code}")
                    if (response.status !in CLIENT_ERRORS) wrong += "B's ID in the body: $call → ${response.status} ${response.code}"
                }
            }
            println("[AT-042] ${staffCalls(target).size * 3 + guestCalls(target).size} path-ID calls, ${wrong.size} not 404")
            assertTrue(
                wrong.isEmpty(),
                "calls that didn't answer 404 (or, with B's ID in a body, weren't refused):\n" + wrong.joinToString("\n"),
            )
            assertEquals(before, snapshot(target), "nothing in restaurant B changed")
        }

    /** B's resources, made where the seed has none: an open session with an order and a waiter call. */
    private data class BTarget(
        val staffId: String,
        val categoryId: String,
        val itemId: String,
        val tableId: String,
        val otherTableId: String,
        val sessionId: String,
        val orderId: String,
        val lineId: String,
        val callId: String,
    ) {
        companion object {
            fun make(
                table: JsonObject,
                other: JsonObject,
            ): BTarget {
                val b = Staging.b
                val guest = b.seat(table.str("id"))
                val item = b.item("Iced tea")
                val order = guest.order(line(item.str("id")))
                val call = guest.expect(CREATED, "POST", "/v1/guest/waiter-calls", buildJsonObject { put("kind", "call_waiter") }).json
                return BTarget(
                    staffId =
                        b.seed.waiters
                            .first()
                            .staffId,
                    categoryId = item.str("category_id"),
                    itemId = item.str("id"),
                    tableId = table.str("id"),
                    otherTableId = other.str("id"),
                    sessionId = guest.sessionId,
                    orderId = order.str("id"),
                    lineId = order.arrOf("lines").single().str("id"),
                    callId = call.str("id"),
                )
            }
        }
    }

    /** Every `/v1/staff` operation with an ID in its path (the endpoint registry), each given a valid body and headers. */
    private fun staffCalls(t: BTarget): List<Call> {
        val v = ifMatch(1)
        val name = buildJsonObject { put("name_en", "x") }
        return listOf(
            Call("GET", "/v1/staff/members/${t.staffId}"),
            Call("PATCH", "/v1/staff/members/${t.staffId}", buildJsonObject { put("display_name", "x") }, v),
            Call("POST", "/v1/staff/members/${t.staffId}/update", buildJsonObject { put("display_name", "x") }, v),
            Call("POST", "/v1/staff/members/${t.staffId}/deactivate"),
            Call("POST", "/v1/staff/members/${t.staffId}/reactivate"),
            Call("POST", "/v1/staff/members/${t.staffId}/invitation"),
            Call("POST", "/v1/staff/members/${t.staffId}/password-reset"),
            Call("PATCH", "/v1/staff/menu/categories/${t.categoryId}", name, v),
            Call("POST", "/v1/staff/menu/categories/${t.categoryId}/update", name, v),
            Call("DELETE", "/v1/staff/menu/categories/${t.categoryId}", headers = v),
            Call("PUT", "/v1/staff/menu/categories/${t.categoryId}/items/order", ids("item_ids", t.itemId)),
            Call("GET", "/v1/staff/menu/items/${t.itemId}"),
            Call("PATCH", "/v1/staff/menu/items/${t.itemId}", name, v),
            Call("POST", "/v1/staff/menu/items/${t.itemId}/update", name, v),
            Call("DELETE", "/v1/staff/menu/items/${t.itemId}", headers = v),
            Call("PUT", "/v1/staff/menu/items/${t.itemId}/sold-out", buildJsonObject { put("sold_out", true) }),
            Call("GET", "/v1/staff/tables/${t.tableId}"),
            Call("PATCH", "/v1/staff/tables/${t.tableId}", buildJsonObject { put("area", "x") }, v),
            Call("POST", "/v1/staff/tables/${t.tableId}/update", buildJsonObject { put("area", "x") }, v),
            Call("DELETE", "/v1/staff/tables/${t.otherTableId}", headers = v),
            Call("GET", "/v1/staff/tables/${t.tableId}/qr-code"),
            Call("POST", "/v1/staff/tables/${t.tableId}/qr-code/rotate"),
            Call("POST", "/v1/staff/tables/${t.otherTableId}/session"),
            Call("GET", "/v1/staff/table-sessions/${t.sessionId}"),
            Call("POST", "/v1/staff/table-sessions/${t.sessionId}/move", buildJsonObject { put("to_table_id", t.otherTableId) }, v),
            Call("POST", "/v1/staff/table-sessions/${t.sessionId}/orders", orderBody(line(t.itemId)), idempotencyKey()),
            Call("GET", "/v1/staff/table-sessions/${t.sessionId}/bill"),
            Call("POST", "/v1/staff/table-sessions/${t.sessionId}/close", buildJsonObject { put("force", true) }, v + idempotencyKey()),
            Call("POST", "/v1/staff/table-sessions/${t.sessionId}/bill/adjustments", adjustment(), v),
            Call("GET", "/v1/staff/orders/${t.orderId}"),
            Call("POST", "/v1/staff/orders/${t.orderId}/status", rejection(), v),
            Call(
                "POST",
                "/v1/staff/orders/${t.orderId}/lines/${t.lineId}/reject",
                buildJsonObject {
                    putJsonObject("reason") { put("code", "other") }
                },
                v,
            ),
            Call("POST", "/v1/staff/waiter-calls/${t.callId}/acknowledge"),
        )
    }

    private fun guestCalls(t: BTarget) =
        listOf(
            Call("GET", "/v1/guest/orders/${t.orderId}"),
            Call("POST", "/v1/guest/orders/${t.orderId}/cancel"),
        )

    /** B's IDs where A's operations take them in the body: A's own session, B's table or item. */
    private fun bodyCalls(
        t: BTarget,
        guest: Guest,
    ): List<Pair<Call, String?>> {
        val session = api.expect(OK, "GET", "/v1/staff/table-sessions/${guest.sessionId}", token = a.waiter).json
        return listOf(
            Call(
                "POST",
                "/v1/staff/table-sessions/${guest.sessionId}/move",
                buildJsonObject {
                    put("to_table_id", t.otherTableId)
                },
                ifMatch(session.int("version")),
            ) to
                a.waiter,
            Call("POST", "/v1/staff/table-sessions/${guest.sessionId}/orders", orderBody(line(t.itemId)), idempotencyKey()) to a.waiter,
            Call("POST", "/v1/guest/orders", orderBody(line(t.itemId)), idempotencyKey()) to guest.token,
            Call("PUT", "/v1/staff/menu/categories/order", ids("category_ids", t.categoryId)) to a.manager,
        )
    }

    /** What A's calls could have changed in B, read by B's own staff. */
    private fun snapshot(t: BTarget): Map<String, Any?> {
        fun get(path: String) = api.expect(OK, "GET", path, token = b.manager).json
        val members = get("/v1/staff/members").arrOf("items").associate { it.str("id") to (it.int("version") to it.str("status")) }
        val tables =
            b.tables().associate {
                it.str("id") to
                    listOf(it.int("version"), it.str("state"), it["session"]?.let { s -> (s as JsonObject).str("id") })
            }
        val order = get("/v1/staff/orders/${t.orderId}")
        val bill = get("/v1/staff/table-sessions/${t.sessionId}/bill")
        val calls =
            api.expect(OK, "GET", "/v1/staff/waiter-calls", token = b.waiter).arrOf("items").associate {
                it.str("id") to
                    it.str("status")
            }
        return mapOf(
            "menu" to b.staffMenu().int("version"),
            "members" to members,
            "tables" to tables,
            "qr" to get("/v1/staff/tables/${t.tableId}/qr-code").int("key_version"),
            "session" to get("/v1/staff/table-sessions/${t.sessionId}").let { it.int("version") to it.obj("table").str("id") },
            "order" to (order.int("version") to order.str("status")),
            "bill" to (bill.int("version") to bill.money("total")),
            "calls" to calls,
        )
    }

    private fun ids(
        field: String,
        id: String,
    ) = buildJsonObject { putJsonArray(field) { add(JsonPrimitive(id)) } }

    /** `rejected` needs its reason, or the call stops at `400` before it looks the order up. */
    private fun rejection() =
        buildJsonObject {
            put("status", "rejected")
            putJsonObject("reason") { put("code", "other") }
        }

    private fun adjustment() =
        buildJsonObject {
            put("amount", vnd(-1_000))
            put("payment_method", "cash")
            put("reason_code", "goodwill")
        }

    private companion object {
        val CLIENT_ERRORS = 400..499
    }
}
