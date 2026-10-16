package io.github.phongmanh.restaurant.tables

import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Guests
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.errorFields
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.obj
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** IT-TBL-01, -02, -05, -06, -07, -12 and -15 (tables §3.1–3.3, §9; FR-001, FR-043, NFR-SEC-02, NFR-SEC-04, NFR-PRIV-01). */
class GuestSessionTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val e = Endpoints.Tables

    @Test
    fun `IT-TBL-01 the first scan opens a session, a second device joins it, the same device twice gets it with two tokens`() =
        tablesApp {
            val a = Tenants.create("Phở Hà Nội", "manager@a.example")
            val table = client.table(a.managerToken, "A-5").str("id")
            val code = client.qrToken(a.managerToken, table)
            val device = UuidV7.next().toString()

            val first = client.scan(code, device)
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals("no-store", first.headers[HttpHeaders.CacheControl], "a bearer token is never cached")
            val grant = first.json()
            assertEquals("Bearer", grant.str("token_type"))
            assertEquals("21600", grant.str("expires_in"))
            val session = grant.obj("session")
            assertEquals("A-5", session.obj("table").str("number"))
            assertEquals(table, session.obj("table").str("id"))
            assertEquals("Phở Hà Nội", session.obj("restaurant").str("name"))
            assertEquals(
                listOf("vi"),
                session
                    .obj("restaurant")
                    .getValue("languages")
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            )

            val again = client.scan(code, device).json()
            val other = client.scan(code).json()
            assertEquals(session.str("id"), again.obj("session").str("id"), "same device, same session")
            assertEquals(session.str("id"), other.obj("session").str("id"), "another device joins")
            assertNotEquals(grant.str("session_token"), again.str("session_token"), "a fresh token")
            listOf(grant, again, other).forEach {
                assertEquals(HttpStatusCode.OK, client.api(e.GET_GUEST_SESSION, it.str("session_token")).status, "every token works")
            }
            assertEquals(1, count("SELECT count(*) FROM table_sessions"))
            assertEquals(listOf("TableSessionOpened"), eventsOf(a.id), "one event, for the session that was created")
            assertEquals(0, count("SELECT count(*) FROM idempotency_keys"), "no stored answer: it would keep a token (§13 Q1)")
        }

    @Test
    fun `IT-TBL-01 a scan needs a QR token and a device ID`() =
        tablesApp {
            assertEquals(listOf("/qr_token", "/device_id"), client.api(e.OPEN_OR_JOIN_SESSION, null, body = "{}").errorFields())
            assertEquals(
                listOf("/device_id"),
                client.api(e.OPEN_OR_JOIN_SESSION, null, body = """{"qr_token": "x", "device_id": "nope"}""").errorFields(),
            )
        }

    @Test
    fun `IT-TBL-02 two first scans at the same moment make one session`() =
        tablesApp {
            val a = Tenants.create("A", "manager@a.example")
            repeat(ROUNDS) { round ->
                val code = client.qrToken(a.managerToken, client.table(a.managerToken, "R$round").str("id"))
                val sessions =
                    coroutineScope {
                        (1..3).map { async { client.scan(code) } }.map { it.await() }
                    }.map {
                        assertEquals(HttpStatusCode.OK, it.status, "round $round")
                        it.json().obj("session").str("id")
                    }
                assertEquals(1, sessions.toSet().size, "round $round")
            }
            assertEquals(ROUNDS, count("SELECT count(*) FROM table_sessions"))
            assertEquals(ROUNDS, eventsOf(a.id).count { it == "TableSessionOpened" })
        }

    @Test
    fun `IT-TBL-05 a disabled table is 409, and a suspended restaurant 403`() =
        tablesApp {
            val a = Tenants.create("A", "manager@a.example")
            val table = client.table(a.managerToken, "1").str("id")
            val code = client.qrToken(a.managerToken, table)
            val (guest, _) = client.seat(code)
            AuthFixtures.sql("UPDATE dining_tables SET enabled = false WHERE id = ?::uuid", table)
            assertEquals("table_unavailable", client.scan(code).problem())
            AuthFixtures.sql("UPDATE dining_tables SET enabled = true WHERE id = ?::uuid", table)

            AuthFixtures.suspend(a.id)
            assertEquals("restaurant_suspended", client.scan(code).problem())
            assertEquals(HttpStatusCode.OK, client.api(e.GET_GUEST_SESSION, guest).status, "reading the session is allowed when suspended")
            assertEquals(
                "restaurant_suspended",
                client.api(e.CALL_WAITER, guest, body = """{"kind": "call_waiter"}""").problem(),
                "calling a waiter isn't",
            )
        }

    @Test
    fun `IT-TBL-06 once the session is closed the next guest call is 401 table_session_closed, and the next scan opens a new session`() =
        tablesApp {
            val a = Tenants.create("A", "manager@a.example")
            val code = client.qrToken(a.managerToken, client.table(a.managerToken, "1").str("id"))
            val (guest, sessionId) = client.seat(code)
            Guests.closeSession(TableSessionId(UUID.fromString(sessionId)))

            val closed = client.api(e.GET_GUEST_SESSION, guest)
            assertEquals(HttpStatusCode.Unauthorized, closed.status)
            assertEquals("table_session_closed", closed.problem())
            val (_, next) = client.seat(code)
            assertNotEquals(sessionId, next, "a new session")
            assertEquals("unauthenticated", client.api(e.GET_GUEST_SESSION, null).problem())
            assertEquals("unauthenticated", client.api(e.GET_GUEST_SESSION, a.managerToken).problem(), "a staff token isn't a guest's")
        }

    @Test
    fun `IT-TBL-07 a guest token never reaches another restaurant's session, and guest tokens are no staff tokens`() =
        tablesApp {
            val a = Tenants.create("A", "manager@a.example")
            val b = Tenants.create("B", "manager@b.example")
            val (bGuest, bSession) = client.seat(client.qrToken(b.managerToken, client.table(b.managerToken, "1").str("id")))
            val (aGuest, _) = client.seat(client.qrToken(a.managerToken, client.table(a.managerToken, "1").str("id")))

            // A token signed for restaurant A that names B's session: the lookup runs under A and finds nothing.
            val crossed = Guests.token(a.id, TableSessionId(UUID.fromString(bSession)))
            assertEquals("table_session_closed", client.api(e.GET_GUEST_SESSION, crossed).problem())
            assertEquals("unauthenticated", client.api(Endpoints.Restaurants.GET_OWN_RESTAURANT, aGuest).problem())
            assertEquals("not_found", client.api(e.GET_TABLE_SESSION, a.managerToken, "sessionId" to bSession).problem())
            val own = client.api(e.GET_GUEST_SESSION, bGuest).json()
            assertEquals(b.id.toString(), own.obj("restaurant").str("id"))
        }

    @Test
    fun `IT-TBL-12 the 11th scan from one device in a minute is 429 with Retry-After`() =
        tablesApp(limiter = null) {
            val a = Tenants.create("A", "manager@a.example")
            val code = client.qrToken(a.managerToken, client.table(a.managerToken, "1").str("id"))
            val device = UuidV7.next().toString()
            repeat(10) { assertEquals(HttpStatusCode.OK, client.scan(code, device, ip = "198.51.100.$it").status, "scan ${it + 1}") }
            val limited = client.scan(code, device, ip = "198.51.100.99")
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("rate_limited", limited.problem())
            assertTrue(limited.headers[HttpHeaders.RetryAfter]!!.toInt() in 1..60)
            assertEquals(HttpStatusCode.OK, client.scan(code, ip = "198.51.100.98").status, "another device still scans")
        }

    @Test
    fun `IT-TBL-15 no device ID, IP or user agent is stored with sessions or calls`() =
        tablesApp {
            val a = Tenants.create("A", "manager@a.example")
            val code = client.qrToken(a.managerToken, client.table(a.managerToken, "1").str("id"))
            val device = UuidV7.next().toString()
            val (guest, _) = client.seat(code, device)
            client.api(e.CALL_WAITER, guest, body = """{"kind": "request_bill"}""")

            val stored =
                listOf("table_sessions", "waiter_calls").flatMap { table ->
                    AuthFixtures.query("SELECT row_to_json(t)::text FROM $table t") { it.getString(1) }
                }
            assertEquals(2, stored.size)
            stored.forEach { row ->
                listOf(device, "203.0.113.7", "restaurant-tests").forEach { assertTrue(it !in row, "$it stored: $row") }
            }
            val columns =
                AuthFixtures.query(
                    "SELECT column_name FROM information_schema.columns WHERE table_name IN ('table_sessions', 'waiter_calls')",
                ) { it.getString(1) }
            assertTrue(columns.none { "device" in it || "ip" == it || "agent" in it }, columns.toString())
        }

    private companion object {
        const val ROUNDS = 5
    }
}
