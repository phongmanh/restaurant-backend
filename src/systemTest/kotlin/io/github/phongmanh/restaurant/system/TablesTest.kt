package io.github.phongmanh.restaurant.system

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** test-plan §5, tables: AT-030 to AT-032 (FR-030 to FR-032), in restaurant A. */
class TablesTest {
    private val a = Staging.a
    private val api = Staging.api

    /**
     * Restaurant A has tables 1–25 from the seed, so this run's twenty are numbered `S<run>-1` to
     * `S<run>-20`, and deleted at the end.
     */
    @Test
    fun `AT-030 twenty new tables, a duplicate refused, and a table with guests can't be deleted`() {
        val prefix = "S${Staging.run}-"
        val created = mutableListOf<JsonObject>()
        try {
            for (n in 1..TABLES) {
                created += api.expect(CREATED, "POST", "/v1/staff/tables", tableBody("$prefix$n"), a.manager).json
            }
            assertEquals((1..TABLES).map { "$prefix$it" }, created.map { it.str("number") })
            val duplicate = api.call("POST", "/v1/staff/tables", tableBody("${prefix}5"), a.manager)
            assertEquals(CONFLICT to "duplicate_name", duplicate.status to duplicate.code, "a second table 5 is refused")

            val seated = created.first()
            val guest = a.seat(seated.str("id"))
            val refused =
                api.call(
                    "DELETE",
                    "/v1/staff/tables/${seated.str("id")}",
                    token = a.manager,
                    headers = ifMatch(current(seated).int("version")),
                )
            assertEquals(CONFLICT to "table_has_open_session", refused.status to refused.code, "a table with guests can't be deleted")
            a.closeSession(guest.sessionId)
        } finally {
            for (table in created) {
                api.expect(
                    NO_CONTENT,
                    "DELETE",
                    "/v1/staff/tables/${table.str("id")}",
                    token = a.manager,
                    headers = ifMatch(current(table).int("version")),
                )
            }
        }
    }

    /**
     * The API's half of FR-031: the printed link is the customer app's `/t/<token>`, and scans from
     * two phones land in the table's one session. Whether the camera opens the installed app or the
     * web app is the client apps' (none exists yet), so that half waits for the owner's run (§9 step 7).
     */
    @Test
    fun `AT-031 the printed code links to the customer app and opens the table's session`() =
        a.withTables(1) { (table) ->
            val printed = api.expect(OK, "GET", "/v1/staff/tables/${table.str("id")}/qr-code", token = a.manager).json
            assertEquals(table.str("number"), printed.str("number"))
            assertTrue(printed.str("url").startsWith("${Staging.customerAppUrl.trimEnd('/')}/t/"), "the link opens the customer app")
            val token = printed.str("url").substringAfter("/t/")
            val withApp = Guest.scan(token)
            val withoutApp = Guest.scan(token)
            assertEquals(table.str("id"), withApp.tableId, "the scan opens table ${table.str("number")}'s session")
            assertEquals(withApp.sessionId, withoutApp.sessionId, "both phones are in the one session")
        }

    @Test
    fun `AT-032 a waiter moves a session with its orders and bill, but not onto an occupied table`() =
        a.withTables(3) { (from, to, occupied) ->
            val guest = a.seat(from.str("id"))
            val order = guest.order(line(a.item("Shaking beef").str("id")))
            a.seat(occupied.str("id"))
            val session = api.expect(OK, "GET", "/v1/staff/table-sessions/${guest.sessionId}", token = a.waiter).json

            val moved = move(session, to.str("id"))
            assertEquals(OK, moved.status)
            assertEquals(to.str("id"), moved.json.obj("table").str("id"))
            val floor = a.tables().associateBy { it.str("id") }
            assertEquals("free", floor.getValue(from.str("id")).str("state"), "the old table shows free")
            assertEquals(guest.sessionId, floor.getValue(to.str("id")).obj("session").str("id"), "the session is on the new table")
            val bill = guest.bill()
            assertEquals(to.str("id"), bill.obj("table").str("id"), "the bill is on the new table")
            assertEquals(order.money("total"), bill.money("total"), "with its orders")
            val orderNow = guest.expect(OK, "GET", "/v1/guest/orders/${order.str("id")}").json
            assertEquals(to.str("id"), orderNow.obj("table").str("id"), "the order moved too")

            val onto = move(moved.json, occupied.str("id"))
            assertEquals(CONFLICT to "table_occupied", onto.status to onto.code, "moving onto an occupied table is refused")
        }

    private fun move(
        session: JsonObject,
        toTableId: String,
    ) = api.call(
        "POST",
        "/v1/staff/table-sessions/${session.str("id")}/move",
        buildJsonObject { put("to_table_id", toTableId) },
        a.waiter,
        ifMatch(session.int("version")),
    )

    private fun current(table: JsonObject) = api.expect(OK, "GET", "/v1/staff/tables/${table.str("id")}", token = a.manager).json

    private fun tableBody(number: String) =
        buildJsonObject {
            put("number", number)
            put("area", "System test")
            put("seats", SEATS)
        }

    private companion object {
        const val TABLES = 20
        const val SEATS = 4
    }
}
