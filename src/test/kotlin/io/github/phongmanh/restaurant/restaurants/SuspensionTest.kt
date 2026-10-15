package io.github.phongmanh.restaurant.restaurants

import io.github.phongmanh.restaurant.Contract
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.RouteGroup
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.accessToken
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.notYetRouted
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** IT-REST-10 and IT-REST-11 (restaurants §6, FR-043). */
class SuspensionTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    @Test
    fun `IT-REST-10 a suspended restaurant's staff sign in and read what §6 allows, and reactivation restores the rest`() =
        authApp {
            val a = Tenants.create("A", "lan@a.example")
            val (_, admin) = Tenants.admin("admin@platform.example")
            val suspend = Endpoints.Admin.SUSPEND_RESTAURANT
            val suspended = client.api(suspend, admin, "restaurantId" to a.id, body = """{"reason": "Unpaid invoice #12 (call Lan)"}""")
            assertEquals(HttpStatusCode.OK, suspended.status)
            assertEquals("suspended", suspended.json().str("status"))
            assertEquals("Unpaid invoice #12 (call Lan)", suspended.json().str("suspension_reason"), "the admin view")
            assertEquals(HttpStatusCode.OK, client.api(suspend, admin, "restaurantId" to a.id, body = """{"reason": "again"}""").status)
            assertEquals("validation_failed", client.api(suspend, admin, "restaurantId" to a.id, body = "{}").problem())

            val manager = client.accessToken("lan@a.example")
            val blocked =
                Endpoints.all.filter { it.group == RouteGroup.STAFF && !it.allowedWhenSuspended && it !in notYetRouted }
            assertTrue(
                Endpoints.Restaurants.UPDATE_OWN_RESTAURANT in blocked && Endpoints.Staff.LIST_MEMBERS in blocked,
                blocked.toString(),
            )
            for (endpoint in blocked) {
                val placeholders = Regex("\\{(\\w+)}").findAll(endpoint.path).map { it.groupValues[1] to UuidV7.next() }.toList()
                val response =
                    client.api(endpoint, manager, *placeholders.toTypedArray(), body = "{}") { header(HttpHeaders.IfMatch, "\"0\"") }
                assertEquals(HttpStatusCode.Forbidden, response.status, endpoint.toString())
                assertEquals("restaurant_suspended", response.problem(), endpoint.toString())
            }
            val own = client.api(Endpoints.Restaurants.GET_OWN_RESTAURANT, manager)
            assertEquals("suspended", own.json().str("status"))
            assertTrue("suspension_reason" !in own.json(), "the reason is for admins only")
            assertEquals(HttpStatusCode.OK, client.api(Endpoints.Audit.LIST_OWN_AUDIT_EVENTS, manager).status)
            assertEquals(HttpStatusCode.OK, client.api(Endpoints.Staff.LIST_RESTAURANT_MEMBERS, admin, "restaurantId" to a.id).status)

            val reactivate = Endpoints.Admin.REACTIVATE_RESTAURANT
            assertEquals("active", client.api(reactivate, admin, "restaurantId" to a.id).json().str("status"))
            assertEquals("active", client.api(reactivate, admin, "restaurantId" to a.id).json().str("status"), "again: no-op")
            assertEquals(HttpStatusCode.OK, client.api(Endpoints.Staff.LIST_MEMBERS, manager).status, "the same session works again")

            val entries =
                AuthFixtures.query("SELECT action, details::text FROM audit_events WHERE action LIKE 'restaurant.%' ORDER BY chain_seq") {
                    it.getString(1) to it.getString(2)
                }
            assertEquals(listOf("restaurant.suspended" to "{}", "restaurant.reactivated" to "{}"), entries, "once each, and no free text")
            assertEquals(HttpStatusCode.NotFound, client.api(reactivate, admin, "restaurantId" to UuidV7.next()).status)
        }

    @Test
    fun `IT-REST-11 every endpoint declares allowedWhenSuspended explicitly, in the registry and the contract`() {
        // The registry's `endpoint()` has no default for it, so the compiler makes every entry decide;
        // here the contract has to say it too, as a boolean on every operation, and agree.
        val byKey = Endpoints.all.associateBy { "${it.method.value} ${it.path}" }
        val undeclared = Contract.operations.filter { it.allowedWhenSuspended !is Boolean }
        assertTrue(undeclared.isEmpty(), "operations without x-allowed-when-suspended: $undeclared")
        val differ =
            Contract.operations.filter {
                byKey
                    .getValue(
                        "${it.method} ${it.path}",
                    ).allowedWhenSuspended != it.allowedWhenSuspended
            }
        assertTrue(differ.isEmpty(), "registry and contract differ: $differ")
    }
}
