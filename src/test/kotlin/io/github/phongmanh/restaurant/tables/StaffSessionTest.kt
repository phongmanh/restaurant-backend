package io.github.phongmanh.restaurant.tables

import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Guests
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.items
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.obj
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.github.phongmanh.restaurant.tables.application.BillSummary
import io.github.phongmanh.restaurant.tables.application.SessionSummaries
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.koin.dsl.module
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** IT-TBL-08 and IT-TBL-09, and staff opening a session (tables §3.4, §6; FR-032, FR-014). */
class StaffSessionTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val e = Endpoints.Tables

    @Test
    fun `staff open a table's session without a scan, or get the open one, a disabled or deleted table is 409`() =
        tablesApp {
            val a = Tenants.create("A", "manager@a.example")
            val (_, waiter) = a.member("waiter@a.example", "waiter")
            val (_, kitchen) = a.member("kitchen@a.example", "kitchen")
            val table = client.table(a.managerToken, "1").str("id")

            val opened = client.api(e.OPEN_SESSION_BY_STAFF, waiter, "tableId" to table)
            assertEquals(HttpStatusCode.Created, opened.status)
            assertEquals("\"0\"", opened.headers[HttpHeaders.ETag])
            val session = opened.json()
            assertEquals(listOf("open", "1"), listOf(session.str("status"), session.obj("table").str("number")))
            val again = client.api(e.OPEN_SESSION_BY_STAFF, a.managerToken, "tableId" to table)
            assertEquals(HttpStatusCode.OK, again.status)
            assertEquals(session.str("id"), again.json().str("id"))
            val (_, joined) = client.seat(client.qrToken(a.managerToken, table))
            assertEquals(session.str("id"), joined, "a guest's scan joins it")

            assertEquals("forbidden", client.api(e.OPEN_SESSION_BY_STAFF, kitchen, "tableId" to table).problem())
            assertEquals(
                HttpStatusCode.OK,
                client.api(e.GET_TABLE_SESSION, kitchen, "sessionId" to session.str("id")).status,
                "all roles read",
            )

            val disabled = client.table(a.managerToken, "2", """, "seats": 2""").str("id")
            client.api(e.UPDATE_TABLE_POST, a.managerToken, "tableId" to disabled, body = """{"enabled": false}""", configure = ifMatch(0))
            assertEquals("table_unavailable", client.api(e.OPEN_SESSION_BY_STAFF, waiter, "tableId" to disabled).problem())
            val deleted = client.table(a.managerToken, "3").str("id")
            client.api(e.DELETE_TABLE, a.managerToken, "tableId" to deleted, configure = ifMatch(0))
            assertEquals("table_unavailable", client.api(e.OPEN_SESSION_BY_STAFF, waiter, "tableId" to deleted).problem())

            assertEquals(listOf("table_session.opened_by_staff"), auditOf(a.id, "table_session."), "audited once, when it was created")
        }

    @Test
    fun `IT-TBL-08 a move to a free table carries the session, an occupied target is 409, guests see the new table`() =
        tablesApp {
            val a = Tenants.create("A", "manager@a.example")
            val (_, waiter) = a.member("waiter@a.example", "waiter")
            val one = client.table(a.managerToken, "1").str("id")
            val two = client.table(a.managerToken, "2").str("id")
            val three = client.table(a.managerToken, "3").str("id")
            val (guest, sessionId) = client.seat(client.qrToken(a.managerToken, one))
            client.seat(client.qrToken(a.managerToken, three))

            suspend fun move(
                to: String,
                version: Int,
            ) = client.api(
                e.MOVE_TABLE_SESSION,
                waiter,
                "sessionId" to sessionId,
                body = """{"to_table_id": "$to"}""",
                configure = ifMatch(version),
            )

            assertEquals("table_occupied", move(three, 0).problem())
            assertEquals("validation_failed", move(one, 0).problem(), "the table it's at")
            assertEquals("precondition_failed", move(two, 5).problem())
            assertEquals(
                "precondition_required",
                client.api(e.MOVE_TABLE_SESSION, waiter, "sessionId" to sessionId, body = """{"to_table_id": "$two"}""").problem(),
            )
            val moved = move(two, 0)
            assertEquals(HttpStatusCode.OK, moved.status)
            assertEquals("\"1\"", moved.headers[HttpHeaders.ETag])
            assertEquals("2", moved.json().obj("table").str("number"))
            assertEquals(
                "2",
                client
                    .api(e.GET_GUEST_SESSION, guest)
                    .json()
                    .obj("table")
                    .str("number"),
                "the guest's next call sees table 2",
            )

            val (_, fresh) = client.seat(client.qrToken(a.managerToken, one))
            assertFalse(fresh == sessionId, "table 1 is free: its next scan opens a new session")
            assertEquals(listOf("table_session.moved"), auditOf(a.id, "table_session.moved"))
            assertEquals(1, eventsOf(a.id).count { it == "TableSessionMoved" })

            Guests.closeSession(TableSessionId(UUID.fromString(sessionId)))
            assertEquals("session_not_open", move(three, 1).problem())

            val nowhere =
                client.api(
                    e.MOVE_TABLE_SESSION,
                    waiter,
                    "sessionId" to UuidV7.next(),
                    body = """{"to_table_id": "$three"}""",
                    configure = ifMatch(0),
                )
            assertEquals(HttpStatusCode.NotFound, nowhere.status, "an unknown session")
            assertEquals("not_found", nowhere.problem())
        }

    @Test
    fun `IT-TBL-08 two concurrent moves of one session - one 200, one 412`() =
        tablesApp {
            val a = Tenants.create("A", "manager@a.example")
            val from = client.table(a.managerToken, "1").str("id")
            val (_, sessionId) = client.seat(client.qrToken(a.managerToken, from))
            val targets = (2..3).map { client.table(a.managerToken, "$it").str("id") }
            val statuses =
                coroutineScope {
                    targets
                        .map { to ->
                            async {
                                client
                                    .api(
                                        e.MOVE_TABLE_SESSION,
                                        a.managerToken,
                                        "sessionId" to sessionId,
                                        body = """{"to_table_id": "$to"}""",
                                        configure = ifMatch(0),
                                    ).status.value
                            }
                        }.map { it.await() }
                }
            assertEquals(listOf(200, 412), statuses.sorted())
        }

    @Test
    fun `IT-TBL-09 the floor view shows free and occupied tables with start time, counts and totals, and flags stale sessions`() {
        var withOrders = emptySet<TableSessionId>()
        val summaries = SessionSummaries { _, _, ids -> ids.filter { it in withOrders }.associateWith { BillSummary(3, 185_000) } }
        tablesApp(overrides = listOf(module { single<SessionSummaries> { summaries } })) {
            val a = Tenants.create("A", "manager@a.example")
            val (_, waiter) = a.member("waiter@a.example", "waiter")
            val (_, kitchen) = a.member("kitchen@a.example", "kitchen")
            val garden = client.table(a.managerToken, "10", """, "area": "Garden"""").str("id")
            client.table(a.managerToken, "2", """, "area": "Garden"""")
            val inside = client.table(a.managerToken, "1").str("id")
            val (guest, gardenSession) = client.seat(client.qrToken(a.managerToken, garden))
            val (_, insideSession) = client.seat(client.qrToken(a.managerToken, inside))
            client.api(e.CALL_WAITER, guest, body = """{"kind": "call_waiter"}""")
            withOrders = setOf(TableSessionId(UUID.fromString(gardenSession)))

            suspend fun floor(): List<JsonObject> = client.api(e.LIST_TABLES, waiter).items().map { it.jsonObject }

            val tables = floor()
            assertEquals(listOf("2", "10", "1"), tables.map { it.str("number") }, "by area, then number as a number; no area last")
            assertEquals(listOf("free", "occupied", "occupied"), tables.map { it.str("state") })
            val occupied = tables[1].obj("session")
            assertEquals(gardenSession, occupied.str("id"))
            assertEquals(
                listOf("3", "185000", "false", "1"),
                listOf(
                    occupied.str("order_count"),
                    occupied.obj("running_total").str("amount"),
                    occupied.str("stale"),
                    occupied.str("open_calls"),
                ),
            )
            assertEquals("0", tables[2].obj("session").str("order_count"), "a session without orders")
            assertFalse("session" in tables[0])

            // Open for 7 h. (Moving the clock instead would expire the staff token too.)
            AuthFixtures.sql("UPDATE table_sessions SET opened_at = opened_at - interval '7 hours'")
            val later = floor()
            assertEquals("true", later[1].obj("session").str("stale"), "open > 6 h with orders")
            assertEquals("false", later[2].obj("session").str("stale"), "no orders: never stale (the job closes it)")
            assertEquals(insideSession, later[2].obj("session").str("id"))
            assertEquals("forbidden", client.api(e.LIST_TABLES, kitchen).problem())
        }
    }
}
