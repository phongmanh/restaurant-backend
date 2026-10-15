package io.github.phongmanh.restaurant.restaurants

import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.TestTenant
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.items
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.obj
import io.github.phongmanh.restaurant.support.signIn
import io.github.phongmanh.restaurant.support.str
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** IT-REST-14 (restaurants §10, NFR-PRIV-01). */
class PrivacyTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val p = Endpoints.Privacy

    /** A waiter with a phone, a sign-in, a reset link from their manager, and an admin to handle the request. */
    private suspend fun ApplicationTestBuilder.setUp(): Triple<TestTenant, StaffId, String> {
        val a = Tenants.create("A", "lan@a.example")
        val (waiter, _) = a.member("mai@a.example", "waiter")
        AuthFixtures.sql("UPDATE staff_accounts SET phone = '+84901234567' WHERE id = ?", waiter.value)
        AuthFixtures.link(waiter, "password_reset")
        client.signIn("mai@a.example")
        client.api(Endpoints.Staff.PASSWORD_RESET_MEMBER, a.managerToken, "staffId" to waiter)
        return Triple(a, waiter, Tenants.admin("admin@platform.example").second)
    }

    @Test
    fun `IT-REST-14 the export holds the personal fields and the member's audit entries, and no secret`() =
        authApp {
            val (_, waiter, admin) = setUp()
            val export = client.api(p.EXPORT_STAFF_DATA, admin, "staffId" to waiter)
            assertEquals(HttpStatusCode.OK, export.status)
            val text = export.bodyAsText()
            listOf("\$argon2id", "password_hash", "token_hash", "totp").forEach { assertFalse(it in text, "no $it in the export") }
            val body = export.json()
            assertEquals("+84901234567", body.obj("account").str("phone"))
            assertEquals("waiter", body.obj("account").str("role"))
            val byAction =
                body
                    .getValue("audit_events")
                    .jsonArray
                    .map { it.jsonObject }
                    .associateBy { it.str("action") }
            assertEquals("203.0.113.7", byAction.getValue("auth.sign_in.succeeded").str("ip"), "their own entry keeps their IP")
            assertFalse("ip" in byAction.getValue("staff.password_reset_issued"), "the manager's IP isn't theirs to get")
            assertTrue("staff.data_exported" in AuthFixtures.auditActions())
        }

    @Test
    fun `IT-REST-14 erasing anonymises at once, ends sign-in and links, and keeps the audit IDs`() =
        authApp {
            val (a, waiter, admin) = setUp()
            val erased = client.api(p.ERASE_STAFF_DATA, admin, "staffId" to waiter)
            assertEquals(HttpStatusCode.OK, erased.status)
            assertFalse(
                erased
                    .json()
                    .getValue("last_manager_removed")
                    .jsonPrimitive.boolean,
            )
            val row =
                AuthFixtures.query(
                    "SELECT email, display_name, phone, password_hash, status, " +
                        "CASE WHEN anonymised_at IS NOT NULL THEN 'anonymised' END FROM staff_accounts WHERE id = ?",
                    waiter.value,
                ) { (1..6).map { i -> it.getString(i) } }
            assertEquals(listOf(listOf("deleted+$waiter@invalid", "Deleted user", null, null, "deactivated", "anonymised")), row)
            val openLinks = "SELECT count(*) FROM staff_link_tokens WHERE staff_id = ? AND superseded_at IS NULL AND used_at IS NULL"
            assertEquals(listOf(0), AuthFixtures.query(openLinks, waiter.value) { it.getInt(1) }, "no link survives")
            val kept =
                AuthFixtures.query("SELECT count(*) FROM audit_events WHERE actor_id = ? OR target_id = ?", waiter.value, waiter.value) {
                    it.getInt(1)
                }
            assertTrue(kept.single() >= 3, "the audit log keeps the pseudonymous ID")
            assertEquals(HttpStatusCode.Unauthorized, client.signIn("mai@a.example").status)
            val listed = client.api(Endpoints.Staff.LIST_MEMBERS, a.managerToken).items().map { it.jsonObject.str("id") }
            assertFalse(waiter.toString() in listed)
            val actors = client.api(Endpoints.Audit.LIST_OWN_AUDIT_EVENTS, a.managerToken).items().map { it.jsonObject.obj("actor") }
            val names = actors.filter { it["id"]?.jsonPrimitive?.content == waiter.toString() }.map { it.str("display_name") }
            assertEquals(setOf("Deleted user"), names.toSet())

            assertEquals(HttpStatusCode.NotFound, client.api(p.EXPORT_STAFF_DATA, admin, "staffId" to waiter).status, "gone")
            assertEquals(HttpStatusCode.NotFound, client.api(p.ERASE_STAFF_DATA, admin, "staffId" to waiter).status)
            assertTrue("staff.erased" in AuthFixtures.auditActions())
        }

    @Test
    fun `erasing the last active manager is allowed and says so, and an admin account isn't a privacy request's subject`() =
        authApp {
            val a = Tenants.create("A", "lan@a.example")
            val (adminId, admin) = Tenants.admin("admin@platform.example")
            val erased = client.api(p.ERASE_STAFF_DATA, admin, "staffId" to a.manager)
            assertTrue(
                erased
                    .json()
                    .getValue("last_manager_removed")
                    .jsonPrimitive.boolean,
            )
            assertEquals(HttpStatusCode.NotFound, client.api(p.EXPORT_STAFF_DATA, admin, "staffId" to adminId).status)
            assertEquals(HttpStatusCode.NotFound, client.api(p.ERASE_STAFF_DATA, admin, "staffId" to adminId).status)
            assertEquals(HttpStatusCode.Unauthorized, client.api(Endpoints.Restaurants.GET_OWN_RESTAURANT, a.managerToken).status)
        }
}
