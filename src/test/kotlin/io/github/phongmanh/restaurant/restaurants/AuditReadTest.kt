package io.github.phongmanh.restaurant.restaurants

import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.MutableClock
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.items
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import java.sql.Timestamp
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** IT-REST-13 (restaurants §9, NFR-SEC-03). */
class AuditReadTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private fun actions(items: JsonArray) = items.map { it.jsonObject.str("action") }

    @Test
    fun `IT-REST-13 a manager reads their own chain without IP, an admin reads any chain with it, and the admin's read is audited`() {
        val clock = MutableClock()
        authApp(clock) {
            val a = Tenants.create("A", "lan@a.example")
            val b = Tenants.create("B", "minh@b.example")
            val (_, admin) = Tenants.admin("admin@platform.example")
            val staff = Endpoints.Staff
            client.api(
                staff.INVITE_MEMBER,
                a.managerToken,
                body = """{"email": "mai@a.example", "display_name": "Mai", "role": "waiter"}""",
            )
            clock.advance(1.minutes)
            client.api(
                staff.INVITE_MEMBER,
                b.managerToken,
                body = """{"email": "hoa@b.example", "display_name": "Hoa", "role": "waiter"}""",
            )
            client.api(Endpoints.Admin.SUSPEND_RESTAURANT, admin, "restaurantId" to b.id, body = """{"reason": "test"}""")

            val own = client.api(Endpoints.Audit.LIST_OWN_AUDIT_EVENTS, a.managerToken).items()
            assertEquals(listOf("staff.invited"), actions(own), "A's chain only")
            val entry = own[0].jsonObject
            assertTrue("ip" !in entry && "user_agent" !in entry, entry.toString())
            assertEquals("Lan", entry["actor"]!!.jsonObject.str("display_name"))
            assertEquals("staff_account", entry["target"]!!.jsonObject.str("type"))

            val read = client.api(HttpMethod.Get, "/v1/admin/audit-events?restaurant_id=${a.id}", admin).items()
            assertEquals("203.0.113.7", read[0].jsonObject.str("ip"))
            assertEquals("restaurant-tests", read[0].jsonObject.str("user_agent"))
            val platform = client.api(HttpMethod.Get, "/v1/admin/audit-events?restaurant_id=platform", admin).items()
            assertEquals(listOf("admin.audit_read"), actions(platform), "the first read is in the platform chain before the second")
            val details =
                AuthFixtures.query("SELECT details::text FROM audit_events WHERE action = 'admin.audit_read' ORDER BY chain_seq") {
                    it.getString(1)
                }
            assertEquals(listOf("""{"chain": "${a.id}"}""", """{"chain": "platform"}"""), details)

            val (_, waiter) = a.member("tuan@a.example", "waiter")
            assertEquals(
                "forbidden",
                client.api(HttpMethod.Get, "/v1/staff/audit-events?from=nonsense", waiter).problem(),
                "403 before 400",
            )
            val missing = client.api(HttpMethod.Get, "/v1/admin/audit-events?restaurant_id=0192aaaa-0000-7000-8000-000000000000", admin)
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertEquals("validation_failed", client.api(HttpMethod.Get, "/v1/admin/audit-events", admin).problem())
        }
    }

    @Test
    fun `the audit read filters by time, action, actor and target, and pages newest first`() {
        val clock = MutableClock()
        authApp(clock) {
            val a = Tenants.create("A", "lan@a.example")
            val (waiter, _) = a.member("mai@a.example", "waiter")
            val members = Endpoints.Staff
            val start = clock.now
            repeat(3) {
                clock.advance(1.minutes)
                client.api(members.PASSWORD_RESET_MEMBER, a.managerToken, "staffId" to waiter)
            }
            clock.advance(1.minutes)
            client.api(members.DEACTIVATE_MEMBER, a.managerToken, "staffId" to waiter)

            fun url(query: String) = "/v1/staff/audit-events?$query"
            val all = client.api(HttpMethod.Get, url("limit=2"), a.managerToken).json()
            assertEquals(listOf("staff.deactivated", "staff.password_reset_issued"), actions(all.getValue("items") as JsonArray))
            val next = client.api(HttpMethod.Get, url("limit=2&cursor=${all.str("next_cursor")}"), a.managerToken).json()
            assertEquals(listOf("staff.password_reset_issued", "staff.password_reset_issued"), actions(next.getValue("items") as JsonArray))
            assertTrue("next_cursor" !in next)

            assertEquals(3, client.api(HttpMethod.Get, url("action=staff.password"), a.managerToken).items().size)
            assertEquals(4, client.api(HttpMethod.Get, url("target_id=$waiter&actor_id=${a.manager}"), a.managerToken).items().size)
            assertEquals(0, client.api(HttpMethod.Get, url("actor_id=$waiter"), a.managerToken).items().size)
            assertEquals(0, client.api(HttpMethod.Get, url("action=staff%25"), a.managerToken).items().size, "% isn't a wildcard")
            val window = "from=${start.plusSeconds(90)}&to=${start.plusSeconds(150)}"
            assertEquals(1, client.api(HttpMethod.Get, url(window), a.managerToken).items().size)

            // An entry from 8 days ago (written directly: only the read is under test): outside the
            // default window of 7 days, inside an explicit one.
            AuthFixtures.sql(
                "INSERT INTO audit_events (id, restaurant_id, chain_seq, occurred_at, actor_type, action, prev_hash, hash) " +
                    "VALUES (?, ?, 1000, ?, 'system', 'old.entry', ?, ?)",
                UuidV7.next(),
                a.id.value,
                Timestamp.from(clock.now.minusSeconds(8L * 86_400)),
                ByteArray(32),
                ByteArray(32),
            )
            assertEquals(0, client.api(HttpMethod.Get, url("action=old."), a.managerToken).items().size, "the last 7 days by default")
            val older = "action=old.&from=${clock.now.minusSeconds(9L * 86_400)}"
            assertEquals(1, client.api(HttpMethod.Get, url(older), a.managerToken).items().size)
            val tooLong = "from=${clock.now.minusSeconds(93L * 86_400)}"
            assertEquals("validation_failed", client.api(HttpMethod.Get, url(tooLong), a.managerToken).problem(), "at most 92 days")
            assertEquals("validation_failed", client.api(HttpMethod.Get, url("from=${clock.now}&to=$start"), a.managerToken).problem())
            assertEquals("validation_failed", client.api(HttpMethod.Get, url("actor_id=abc"), a.managerToken).problem())
        }
    }
}
