package io.github.phongmanh.restaurant.restaurants

import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.TestKeys
import io.github.phongmanh.restaurant.support.accessToken
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.items
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.obj
import io.github.phongmanh.restaurant.support.postJson
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.signIn
import io.github.phongmanh.restaurant.support.str
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** IT-REST-05 to IT-REST-09 and IT-REST-15 (restaurants §7, FR-041). */
class StaffMembersTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val e = Endpoints.Staff

    private suspend fun HttpClient.invite(
        token: String,
        email: String,
        role: String,
    ) = api(e.INVITE_MEMBER, token, body = """{"email": "$email", "display_name": "Mai", "role": "$role"}""")

    private suspend fun HttpClient.accept(link: String) =
        postJson("/v1/auth/invitations/accept", """{"token": "${link.substringAfter("#")}", "password": "a long enough passphrase"}""")

    private fun auditActions(restaurant: String) =
        AuthFixtures.query(
            "SELECT action FROM audit_events WHERE restaurant_id = ?::uuid ORDER BY chain_seq",
            restaurant,
        ) { it.getString(1) }

    @Test
    fun `IT-REST-05 an invited member signs in with their role, while a used email and a non-manager are refused`() =
        authApp {
            val a = Tenants.create("A", "manager@a.example")
            val b = Tenants.create("B", "manager@b.example")
            val invited = client.invite(a.managerToken, "Mai@A.example", "waiter")
            assertEquals(HttpStatusCode.Created, invited.status)
            val body = invited.json()
            val member = body.obj("member")
            assertEquals("/v1/staff/members/${member.str("id")}", invited.headers[HttpHeaders.Location])
            assertEquals("invited", member.str("status"))
            assertTrue(body.obj("invitation").str("link").startsWith("${TestKeys.STAFF_APP}/invite#"))

            assertEquals(HttpStatusCode.NoContent, client.accept(body.obj("invitation").str("link")).status)
            val waiter = client.accessToken("mai@a.example", "a long enough passphrase")
            assertEquals("waiter", client.api(io.ktor.http.HttpMethod.Get, "/v1/auth/me", waiter).json().str("role"))

            assertEquals("email_in_use", client.invite(b.managerToken, "mai@a.example", "kitchen").problem(), "used in another restaurant")
            assertEquals("email_in_use", client.invite(a.managerToken, "manager@b.example", "kitchen").problem())
            val (_, kitchen) = a.member("kitchen@a.example", "kitchen")
            for (token in listOf(waiter, kitchen)) {
                val refused = client.invite(token, "new@a.example", "waiter")
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertEquals("forbidden", refused.problem())
                assertEquals("forbidden", client.api(e.LIST_MEMBERS, token).problem())
            }
            val bad = client.api(e.INVITE_MEMBER, kitchen, body = """{"email": "x"}""")
            assertEquals("forbidden", bad.problem(), "the role is checked before the input (core-and-auth §3)")
            assertEquals(listOf("staff.invited"), auditActions(a.id.toString()).filter { it.startsWith("staff.") })
        }

    @Test
    fun `IT-REST-06 a role change ends the member's sessions and is audited with from and to`() =
        authApp {
            val a = Tenants.create("A", "manager@a.example")
            val (waiterId, waiter) = a.member("waiter@a.example", "waiter")
            assertEquals("\"0\"", client.api(e.GET_MEMBER, a.managerToken, "staffId" to waiterId).headers[HttpHeaders.ETag])

            val change = """{"role": "kitchen", "phone": "+84901234567"}"""
            val promoted = client.api(e.UPDATE_MEMBER, a.managerToken, "staffId" to waiterId, body = change) { ifMatch(0) }
            assertEquals(HttpStatusCode.OK, promoted.status)
            assertEquals("\"1\"", promoted.headers[HttpHeaders.ETag])
            assertEquals("kitchen", promoted.json().str("role"))
            assertEquals(HttpStatusCode.Unauthorized, client.api(Endpoints.Restaurants.GET_OWN_RESTAURANT, waiter).status)

            val rename = """{"display_name": "Mai"}"""
            val stale = client.api(e.UPDATE_MEMBER, a.managerToken, "staffId" to waiterId, body = rename) { ifMatch(0) }
            assertEquals("precondition_failed", stale.problem())
            assertEquals(
                "precondition_required",
                client.api(e.UPDATE_MEMBER, a.managerToken, "staffId" to waiterId, body = rename).problem(),
            )

            val details = AuthFixtures.query("SELECT details::text FROM audit_events WHERE action = 'staff.updated'") { it.getString(1) }
            assertEquals(
                listOf("""{"role": {"to": "kitchen", "from": "waiter"}, "fields": ["phone", "role"]}"""),
                details,
                "no values but the role",
            )
            assertEquals(listOf("role_changed"), revokeReasons(waiterId))
        }

    @Test
    fun `IT-REST-06 a deactivation ends the member's sessions and sign-in, and reactivation restores the old password`() =
        authApp {
            val a = Tenants.create("A", "manager@a.example")
            val (waiterId, waiter) = a.member("waiter@a.example", "waiter")
            val deactivated = client.api(e.DEACTIVATE_MEMBER, a.managerToken, "staffId" to waiterId)
            assertEquals("deactivated", deactivated.json().str("status"))
            assertEquals(HttpStatusCode.Unauthorized, client.api(Endpoints.Restaurants.GET_OWN_RESTAURANT, waiter).status)
            assertEquals(HttpStatusCode.Unauthorized, client.signIn("waiter@a.example").status)
            val again = client.api(e.DEACTIVATE_MEMBER, a.managerToken, "staffId" to waiterId)
            assertEquals("deactivated", again.json().str("status"), "again: no change")

            val reactivated = client.api(e.REACTIVATE_MEMBER, a.managerToken, "staffId" to waiterId)
            assertEquals("active", reactivated.json().str("status"))
            assertEquals(HttpStatusCode.OK, client.signIn("waiter@a.example").status, "the old password")
            assertEquals(listOf("staff.deactivated", "staff.reactivated"), auditActions(a.id.toString()).filter { it.startsWith("staff.") })
            assertEquals(listOf("deactivated"), revokeReasons(waiterId))
        }

    @Test
    fun `IT-REST-07 the last manager can't demote or deactivate themselves`() =
        authApp {
            val a = Tenants.create("A", "lan@a.example")
            val self = client.api(e.UPDATE_MEMBER, a.managerToken, "staffId" to a.manager, body = DEMOTE) { ifMatch(0) }
            assertEquals(HttpStatusCode.Conflict, self.status)
            assertEquals("last_manager", self.problem())
            assertEquals("last_manager", client.api(e.DEACTIVATE_MEMBER, a.managerToken, "staffId" to a.manager).problem())
        }

    @Test
    fun `IT-REST-07 two managers demoting each other at once leave one`() =
        authApp {
            repeat(5) { round ->
                TestDatabase.truncate()
                val r = Tenants.create("A$round", "one$round@a.example")
                val (twoId, two) = r.member("two$round@a.example", "manager")
                val statuses =
                    coroutineScope {
                        listOf(
                            async { client.api(e.UPDATE_MEMBER, r.managerToken, "staffId" to twoId, body = DEMOTE) { ifMatch(0) } },
                            async { client.api(e.UPDATE_MEMBER, two, "staffId" to r.manager, body = DEMOTE) { ifMatch(0) } },
                        ).map { it.await().status }
                    }
                // The loser is refused by the rule, or because the winner's change ended its session.
                assertEquals(1, statuses.count { it == HttpStatusCode.OK }, "round $round: $statuses")
                val managers =
                    AuthFixtures.query(
                        "SELECT count(*) FROM staff_accounts WHERE restaurant_id = ? AND role = 'manager' AND status = 'active'",
                        r.id.value,
                    ) { it.getInt(1) }
                assertEquals(listOf(1), managers, "round $round")
            }
        }

    @Test
    fun `IT-REST-07 an admin isn't bound by the rule, and the entry says no manager is left`() =
        authApp {
            val r = Tenants.create("A", "lan@a.example")
            val (_, admin) = Tenants.admin("admin@platform.example")
            val removed = client.api(e.DEACTIVATE_RESTAURANT_MEMBER, admin, "restaurantId" to r.id, "staffId" to r.manager)
            assertEquals(HttpStatusCode.OK, removed.status)
            val details =
                AuthFixtures.query(
                    "SELECT details::text FROM audit_events WHERE action = 'staff.deactivated'",
                ) { it.getString(1) }
            assertEquals(listOf("""{"last_manager_removed": true}"""), details)
        }

    private fun HttpRequestBuilder.ifMatch(version: Int) = header(HttpHeaders.IfMatch, "\"$version\"")

    private fun revokeReasons(id: StaffId) =
        AuthFixtures.query("SELECT DISTINCT revoke_reason FROM staff_sessions WHERE staff_id = ? AND revoked_at IS NOT NULL", id.value) {
            it.getString(1)
        }

    private companion object {
        const val DEMOTE = """{"role": "waiter"}"""
    }

    @Test
    fun `IT-REST-08 a staff ID of restaurant B is 404 on every member route, and B is unchanged`() =
        authApp {
            val a = Tenants.create("A", "manager@a.example")
            val b = Tenants.create("B", "manager@b.example")
            val (bWaiter, bToken) = b.member("waiter@b.example", "waiter")
            AuthFixtures.link(bWaiter, "invitation")
            val before =
                AuthFixtures.query("SELECT version, status FROM staff_accounts WHERE id = ?", bWaiter.value) {
                    it.getInt(1) to
                        it.getString(2)
                }

            for (endpoint in listOf(
                e.GET_MEMBER,
                e.UPDATE_MEMBER,
                e.DEACTIVATE_MEMBER,
                e.REACTIVATE_MEMBER,
                e.INVITATION_MEMBER,
                e.PASSWORD_RESET_MEMBER,
            )) {
                val response =
                    client.api(endpoint, a.managerToken, "staffId" to bWaiter, body = """{"display_name": "X"}""") {
                        header(HttpHeaders.IfMatch, "\"0\"")
                    }
                assertEquals(HttpStatusCode.NotFound, response.status, endpoint.toString())
                assertEquals("not_found", response.problem(), endpoint.toString())
            }
            assertEquals(HttpStatusCode.NotFound, client.api(e.GET_MEMBER, a.managerToken, "staffId" to "not-a-uuid").status)
            assertTrue(
                client.api(e.LIST_MEMBERS, a.managerToken).items().none {
                    it.jsonObject["id"]!!.jsonPrimitive.content ==
                        bWaiter.toString()
                },
            )

            val after =
                AuthFixtures.query("SELECT version, status FROM staff_accounts WHERE id = ?", bWaiter.value) {
                    it.getInt(1) to
                        it.getString(2)
                }
            assertEquals(before, after)
            assertEquals(HttpStatusCode.OK, client.api(Endpoints.Restaurants.GET_OWN_RESTAURANT, bToken).status, "B's sessions live on")
            assertEquals(
                1,
                AuthFixtures
                    .query("SELECT count(*) FROM staff_link_tokens WHERE staff_id = ? AND superseded_at IS NULL", bWaiter.value) {
                        it.getInt(1)
                    }.single(),
            )
            assertTrue(
                AuthFixtures.query("SELECT action FROM audit_events WHERE restaurant_id = ?", a.id.value) { it.getString(1) }.isEmpty(),
            )
        }

    @Test
    fun `IT-REST-09 a manager can't issue a reset for another manager, an admin can, and links follow the member's status`() =
        authApp {
            val a = Tenants.create("A", "lan@a.example")
            val (otherManager, _) = a.member("minh@a.example", "manager")
            val (waiter, _) = a.member("mai@a.example", "waiter")
            val (_, admin) = Tenants.admin("admin@platform.example")

            val refused = client.api(e.PASSWORD_RESET_MEMBER, a.managerToken, "staffId" to otherManager)
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals(
                "forbidden",
                client.api(e.PASSWORD_RESET_MEMBER, a.managerToken, "staffId" to a.manager).problem(),
                "nor themselves",
            )
            val byAdmin = client.api(e.PASSWORD_RESET_RESTAURANT_MEMBER, admin, "restaurantId" to a.id, "staffId" to otherManager)
            assertEquals(HttpStatusCode.Created, byAdmin.status)
            assertTrue(byAdmin.json().str("link").startsWith("${TestKeys.STAFF_APP}/reset-password#"))

            val forWaiter = client.api(e.PASSWORD_RESET_MEMBER, a.managerToken, "staffId" to waiter)
            assertEquals(HttpStatusCode.Created, forWaiter.status)
            assertEquals("member_state_conflict", client.api(e.INVITATION_MEMBER, a.managerToken, "staffId" to waiter).problem())

            val invited = client.invite(a.managerToken, "new@a.example", "kitchen").json()
            val newId = invited.obj("member").str("id")
            val firstLink = invited.obj("invitation").str("link")
            assertEquals("member_state_conflict", client.api(e.PASSWORD_RESET_MEMBER, a.managerToken, "staffId" to newId).problem())
            val reissued = client.api(e.INVITATION_MEMBER, a.managerToken, "staffId" to newId)
            assertEquals(HttpStatusCode.Created, reissued.status)
            assertEquals("link_invalid", client.accept(firstLink).problem(), "the old link stops working")
            assertEquals(HttpStatusCode.NoContent, client.accept(reissued.json().str("link")).status)
            assertEquals(
                listOf("staff.password_reset_issued", "staff.password_reset_issued", "staff.invited", "staff.invitation_reissued"),
                auditActions(a.id.toString()).filter { it.startsWith("staff.") },
            )
        }

    @Test
    fun `IT-REST-15 the 101st account is refused, and so is the 31st link in an hour`() =
        authApp(limiter = null) {
            val a = Tenants.create("A", "lan@a.example")
            repeat(98) { AuthFixtures.staff(a.id, "s$it@a.example", "waiter") }
            val (deactivated, _) = a.member("gone@a.example", "waiter")
            AuthFixtures.sql("UPDATE staff_accounts SET status = 'deactivated', deactivated_at = now() WHERE id = ?", deactivated.value)
            assertEquals(HttpStatusCode.Created, client.invite(a.managerToken, "s99@a.example", "waiter").status, "the 100th seat")
            val full = client.invite(a.managerToken, "s100@a.example", "waiter")
            assertEquals(HttpStatusCode.Conflict, full.status)
            assertEquals("member_limit_reached", full.problem())
            assertEquals("member_limit_reached", client.api(e.REACTIVATE_MEMBER, a.managerToken, "staffId" to deactivated).problem())

            // Two links so far (one per invitation attempt); 28 more fit in the hour.
            val waiter =
                AuthFixtures
                    .query(
                        "SELECT id FROM staff_accounts WHERE email = 's0@a.example'",
                    ) { it.getObject(1).toString() }
                    .single()
            repeat(
                28,
            ) { assertEquals(HttpStatusCode.Created, client.api(e.PASSWORD_RESET_MEMBER, a.managerToken, "staffId" to waiter).status) }
            val limited = client.api(e.PASSWORD_RESET_MEMBER, a.managerToken, "staffId" to waiter)
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("rate_limited", limited.problem())
            assertTrue(limited.headers[HttpHeaders.RetryAfter]!!.toLong() in 1..3600)
        }

    @Test
    fun `the member list pages by name and filters by status and role`() =
        authApp {
            val a = Tenants.create("A", "lan@a.example")
            listOf("Bảo" to "waiter", "Chi" to "kitchen", "Dũng" to "waiter").forEach { (name, role) ->
                val id = AuthFixtures.staff(a.id, "${name.length}$role@a.example".lowercase(), role)
                AuthFixtures.sql("UPDATE staff_accounts SET display_name = ? WHERE id = ?", name, id.value)
            }
            val first = client.api(io.ktor.http.HttpMethod.Get, "/v1/staff/members?limit=2", a.managerToken).json()
            val cursor = first.str("next_cursor")
            val second = client.api(io.ktor.http.HttpMethod.Get, "/v1/staff/members?limit=2&cursor=$cursor", a.managerToken).json()
            val names =
                (first.getValue("items") as kotlinx.serialization.json.JsonArray) +
                    (second.getValue("items") as kotlinx.serialization.json.JsonArray)
            assertEquals(listOf("Bảo", "Chi", "Dũng", "Lan"), names.map { it.jsonObject["display_name"]!!.jsonPrimitive.content })
            assertTrue("next_cursor" !in second)
            val waiters = client.api(io.ktor.http.HttpMethod.Get, "/v1/staff/members?role=waiter&status=active", a.managerToken).items()
            assertEquals(2, waiters.size)
            assertEquals(
                "validation_failed",
                client.api(io.ktor.http.HttpMethod.Get, "/v1/staff/members?role=chef", a.managerToken).problem(),
            )
            assertEquals(
                "validation_failed",
                client.api(io.ktor.http.HttpMethod.Get, "/v1/staff/members?cursor=nope", a.managerToken).problem(),
            )
        }
}
