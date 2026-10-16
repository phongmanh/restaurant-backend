package io.github.phongmanh.restaurant.tables

import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.TestKeys
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.github.phongmanh.restaurant.tables.application.EmptyBills
import io.ktor.http.HttpStatusCode
import org.koin.dsl.module
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** IT-TBL-13 (tables §3.5, §13 Q2) and the job route's cron secret (core-and-auth §2). */
class AutoCloseTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val job = Endpoints.Jobs.SESSION_AUTO_CLOSE

    @Test
    fun `IT-TBL-13 the job closes empty sessions idle for 2 h as the system, and leaves sessions with orders or recent activity open`() {
        var withOrders = emptySet<TableSessionId>()
        val bills = EmptyBills { _, _, id -> id !in withOrders }
        tablesApp(overrides = listOf(module { single<EmptyBills> { bills } })) {
            val a = Tenants.create("A", "manager@a.example")
            val b = Tenants.create("B", "manager@b.example")

            suspend fun seated(
                tenant: io.github.phongmanh.restaurant.support.TestTenant,
                number: String,
            ): Pair<String, String> = client.seat(client.qrToken(tenant.managerToken, client.table(tenant.managerToken, number).str("id")))

            val (idleGuest, idle) = seated(a, "1")
            val (_, recent) = seated(a, "2")
            val (_, ordered) = seated(a, "3")
            val (_, otherRestaurant) = seated(b, "1")
            client.api(Endpoints.Tables.CALL_WAITER, idleGuest, body = """{"kind": "call_waiter"}""")
            withOrders = setOf(TableSessionId(UUID.fromString(ordered)))
            listOf(idle, ordered, otherRestaurant).forEach {
                AuthFixtures.sql("UPDATE table_sessions SET last_activity_at = now() - interval '3 hours' WHERE id = ?::uuid", it)
            }
            AuthFixtures.sql("UPDATE table_sessions SET last_activity_at = now() - interval '90 minutes' WHERE id = ?::uuid", recent)

            val run = client.job(job, TestKeys.CRON_SECRET)
            assertEquals(HttpStatusCode.OK, run.status)
            assertEquals(listOf("2", "2"), run.json().let { listOf(it.str("restaurants"), it.str("closed")) })

            fun status(id: String) =
                AuthFixtures
                    .query("SELECT status || '/' || coalesce(close_reason, '') FROM table_sessions WHERE id = ?::uuid", id) {
                        it.getString(1)
                    }.single()
            assertEquals("closed/abandoned_empty", status(idle))
            assertEquals("closed/abandoned_empty", status(otherRestaurant))
            assertEquals("open/", status(recent), "idle only 90 minutes")
            assertEquals("open/", status(ordered), "a session with orders is never closed by the job")
            assertEquals(
                listOf("closed_with_session"),
                AuthFixtures.query("SELECT c.status FROM waiter_calls c WHERE c.session_id = ?::uuid", idle) { it.getString(1) },
            )
            assertEquals("table_session_closed", client.api(Endpoints.Tables.GET_GUEST_SESSION, idleGuest).problem())

            val entries =
                AuthFixtures.query(
                    "SELECT actor_type || ':' || coalesce(actor_id::text, '-') FROM audit_events " +
                        "WHERE action = 'table_session.auto_closed' AND target_id = ?::uuid",
                    idle,
                ) { it.getString(1) }
            assertEquals(listOf("system:-"), entries)
            assertEquals(1, eventsOf(a.id).count { it == "TableSessionClosed" })

            assertEquals("0", client.job(job, TestKeys.CRON_SECRET).json().str("closed"), "a second run finds nothing")
        }
    }

    @Test
    fun `a job needs the cron secret`() =
        tablesApp {
            assertEquals(HttpStatusCode.Unauthorized, client.job(job, null).status)
            assertEquals(HttpStatusCode.Unauthorized, client.job(job, TestKeys.CRON_SECRET + "x").status)
            assertEquals("unauthenticated", client.job(job, "short").problem())
            val staff = Tenants.create("A", "manager@a.example")
            assertEquals(HttpStatusCode.Unauthorized, client.job(job, staff.managerToken).status, "a staff token isn't the secret")
            assertEquals(HttpStatusCode.OK, client.job(job, TestKeys.CRON_SECRET).status)
        }
}
