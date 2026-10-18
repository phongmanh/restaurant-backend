package io.github.phongmanh.restaurant.system

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * test-plan §4 ST-15 (NFR-PRIV-01), the runbook's dry run: deploy/README.md's "Staff data requests",
 * done for a member this run invites (restaurants §10). CI checks the same rules (IT-REST-14); this
 * run follows the runbook's steps on staging.
 */
class StaffDataRequestTest {
    private val a = Staging.a
    private val api = Staging.api

    @Test
    fun `ST-15 an admin exports a member's personal data, then erases it, and the member is gone at once`() {
        val member = a.invite("st15", "waiter")
        val admin = Staging.adminToken()
        val path = "/v1/admin/staff-accounts/${member.staffId}"

        val export = api.expect(OK, "GET", "$path/export", token = admin).json
        assertEquals(member.email, export.obj("account").str("email"), "the export holds the personal fields")
        val fields = export.obj("account").keys
        assertTrue(fields.none { f -> SECRET_FIELDS.any { it in f } }, "the export holds no hash, secret or token: $fields")
        assertTrue(export.arrOf("audit_events").isNotEmpty(), "and the audit entries about the member")

        val erased = api.expect(OK, "POST", "$path/erase", token = admin).json
        assertEquals(JsonPrimitive(false), erased["last_manager_removed"], "A keeps its manager")
        val signIn =
            api.call(
                "POST",
                "/v1/auth/sign-in",
                buildJsonObject {
                    put("email", member.email)
                    put("password", requireNotNull(member.password))
                },
            )
        assertEquals(UNAUTHORIZED, signIn.status, "an erased member can't sign in")
        // An anonymised account is no longer a member the manager sees (restaurants §7); its ID stays in the audit log.
        assertEquals(NOT_FOUND, api.call("GET", "/v1/staff/members/${member.staffId}", token = a.manager).status, "the member is gone")
        val listed = api.expect(OK, "GET", "/v1/staff/members?limit=200", token = a.manager).arrOf("items").map { it.str("email") }
        assertTrue(member.email !in listed, "and not listed")

        val actions =
            api
                .expect(OK, "GET", "/v1/staff/audit-events?target_id=${member.staffId}", token = a.manager)
                .arrOf("items")
                .map { it.str("action") }
        for (action in listOf("staff.data_exported", "staff.erased")) assertTrue(action in actions, "audited: $action (has $actions)")
        println("[ST-15] export and erasure of one member: ${export.arrOf("audit_events").size} audit entries exported, then erased")
    }

    private companion object {
        val SECRET_FIELDS = listOf("password", "totp", "token", "hash")
    }
}
