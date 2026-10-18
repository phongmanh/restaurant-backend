package io.github.phongmanh.restaurant.system

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** test-plan §5, staff and restaurants: AT-010, AT-040, AT-041 and AT-044. */
class StaffAccessTest {
    private val a = Staging.a
    private val api = Staging.api

    /**
     * The API's half of FR-010: what each role may do, as the staff app would offer it. The app's
     * screens themselves wait for the owner's run (§9 step 7); no client app exists yet.
     */
    @Test
    fun `AT-010 each role reaches only its functions, and a deactivated waiter is locked out at once`() {
        val roles = mapOf("manager" to a.manager, "waiter" to a.waiter, "kitchen" to a.kitchen)
        for ((role, token) in roles) {
            assertEquals(role, api.expect(OK, "GET", "/v1/auth/me", token = token).json.str("role"), "$role signs in as $role")
        }
        val item = a.item("Iced tea").str("id")
        for ((role, token) in roles - "manager") {
            val edit = api.call("POST", "/v1/staff/menu/items/$item/update", buildJsonObject { put("name_en", "x") }, token, ifMatch(1))
            assertEquals(FORBIDDEN, edit.status, "$role can't open menu editing")
            assertEquals(FORBIDDEN, api.call("GET", "/v1/staff/members", token = token).status, "$role can't manage staff")
        }
        assertEquals(FORBIDDEN, api.call("POST", "/v1/staff/tables/${a.seed.tableIds.first()}/session", token = a.kitchen).status)
        api.expect(OK, "GET", "/v1/staff/members", token = a.manager)
        api.expect(OK, "GET", "/v1/staff/orders", token = a.kitchen)
        api.expect(OK, "GET", "/v1/staff/tables", token = a.waiter)

        val off = requireNotNull(a.seed.deactivated)
        val signIn = api.call("POST", "/v1/auth/sign-in", credentials(off.email, requireNotNull(off.password)))
        assertEquals(UNAUTHORIZED to "invalid_credentials", signIn.status to signIn.code, "the deactivated waiter can't sign in")

        val waiter = a.invite("at010", "waiter")
        val token = Staging.flows.signIn(waiter.email, requireNotNull(waiter.password))
        api.expect(OK, "GET", "/v1/staff/orders", token = token)
        api.expect(OK, "POST", "/v1/staff/members/${waiter.staffId}/deactivate", token = a.manager)
        val after = api.call("GET", "/v1/staff/orders", token = token)
        assertEquals(UNAUTHORIZED to "unauthenticated", after.status to after.code, "the deactivated waiter's open session ends at once")
    }

    @Test
    fun `AT-040 an admin onboards restaurant C, its manager signs in to an empty restaurant, A's manager can't reach admin`() {
        val email = "manager.c.${Staging.run}@${Staging.DOMAIN}"
        val onboarded =
            api
                .expect(
                    CREATED,
                    "POST",
                    "/v1/admin/restaurants",
                    buildJsonObject {
                        put("name", "Nhà hàng C ${Staging.run} (system test)")
                        put("address", "C Lê Lợi, Quận 1, TP. Hồ Chí Minh")
                        put("time_zone", "Asia/Ho_Chi_Minh")
                        putJsonArray("languages") { add(JsonPrimitive("vi")) }
                        putJsonObject("first_manager") {
                            put("email", email)
                            put("display_name", "Quản lý C")
                        }
                    },
                    Staging.adminToken(),
                    idempotencyKey(),
                ).json
        val password = UUID.randomUUID().toString()
        Staging.flows.acceptInvitation(Staging.flows.tokenOf(onboarded.obj("invitation")), password)
        val manager = Staging.flows.signIn(email, password)
        val me = api.expect(OK, "GET", "/v1/auth/me", token = manager).json
        assertEquals("manager", me.str("role"))
        assertEquals(onboarded.obj("restaurant").str("id"), me.obj("restaurant").str("id"), "the manager is in restaurant C")
        assertTrue(api.expect(OK, "GET", "/v1/staff/tables", token = manager).arrOf("items").isEmpty(), "no tables yet")
        assertTrue(api.expect(OK, "GET", "/v1/staff/menu", token = manager).arrOf("categories").isEmpty(), "no menu yet")
        assertTrue(api.expect(OK, "GET", "/v1/staff/orders", token = manager).arrOf("items").isEmpty(), "no orders yet")
        assertEquals(1, api.expect(OK, "GET", "/v1/staff/members", token = manager).arrOf("items").size, "only the manager")

        // A staff token on an admin route answers 404, so the admin routes look absent (core-and-auth §6).
        for (path in listOf("/v1/admin/restaurants", "/v1/admin/restaurants/${a.id}", "/v1/admin/audit-events")) {
            val response = api.call("GET", path, token = a.manager)
            assertEquals(NOT_FOUND to "not_found", response.status to response.code, "A's manager can't reach $path")
        }
    }

    @Test
    fun `AT-041 a member invited, made kitchen and deactivated, each change audited, and the last manager stays`() {
        val member = a.invite("at041", "waiter")
        var current = api.expect(OK, "GET", "/v1/staff/members/${member.staffId}", token = a.manager).json
        assertEquals("waiter" to "active", current.str("role") to current.str("status"))
        val token = Staging.flows.signIn(member.email, requireNotNull(member.password))

        current =
            api
                .expect(
                    OK,
                    "POST",
                    "/v1/staff/members/${member.staffId}/update",
                    buildJsonObject {
                        put("role", "kitchen")
                    },
                    a.manager,
                    ifMatch(current.int("version")),
                ).json
        assertEquals("kitchen", current.str("role"))
        assertEquals(UNAUTHORIZED, api.call("GET", "/v1/auth/me", token = token).status, "the role change ended their session")
        val asKitchen = Staging.flows.signIn(member.email, member.password)
        assertEquals("kitchen", api.expect(OK, "GET", "/v1/auth/me", token = asKitchen).json.str("role"), "they now sign in as kitchen")

        api.expect(OK, "POST", "/v1/staff/members/${member.staffId}/deactivate", token = a.manager)
        val signIn = api.call("POST", "/v1/auth/sign-in", credentials(member.email, member.password))
        assertEquals(UNAUTHORIZED, signIn.status, "deactivated: they can't sign in")

        val audit = api.expect(OK, "GET", "/v1/staff/audit-events?target_id=${member.staffId}", token = a.manager).arrOf("items")
        val actions = audit.map { it.str("action") }
        for (action in listOf("staff.invited", "staff.updated", "staff.deactivated")) {
            assertTrue(action in actions, "the audit log has $action (it has $actions)")
        }

        val self = api.expect(OK, "GET", "/v1/staff/members/${a.seed.manager.staffId}", token = a.manager).json
        val demote =
            api.call(
                "POST",
                "/v1/staff/members/${self.str("id")}/update",
                buildJsonObject {
                    put("role", "waiter")
                },
                a.manager,
                ifMatch(self.int("version")),
            )
        assertEquals(CONFLICT to "last_manager", demote.status to demote.code, "the last manager can't step down")
        val leave = api.call("POST", "/v1/staff/members/${self.str("id")}/deactivate", token = a.manager)
        assertEquals(CONFLICT to "last_manager", leave.status to leave.code, "the last manager can't deactivate themselves")
    }

    @Test
    fun `AT-044 a manager changes the opening hours and time zone, staff see it, a waiter can't change them`() {
        val before = api.expect(OK, "GET", "/v1/staff/restaurant", token = a.manager).json
        val hours =
            buildJsonObject {
                putJsonArray("mon") {
                    add(
                        buildJsonObject {
                            put("opens", "10:00")
                            put("closes", "22:30")
                        },
                    )
                }
            }
        try {
            updateRestaurant(
                before,
                buildJsonObject {
                    put("opening_hours", hours)
                    put("time_zone", OTHER_ZONE)
                },
            )
            val seen = api.expect(OK, "GET", "/v1/staff/restaurant", token = a.waiter).json
            assertEquals(OTHER_ZONE, seen.str("time_zone"), "the staff app shows the new time zone")
            assertEquals(hours, seen.obj("opening_hours").filterKeys { it == "mon" }.let(::JsonObject), "and the new hours")
            val waiter =
                api.call(
                    "POST",
                    "/v1/staff/restaurant/update",
                    buildJsonObject { put("time_zone", "UTC") },
                    a.waiter,
                    ifMatch(seen.int("version")),
                )
            assertEquals(FORBIDDEN, waiter.status, "a waiter can't change them")
        } finally {
            updateRestaurant(
                api.expect(OK, "GET", "/v1/staff/restaurant", token = a.manager).json,
                buildJsonObject {
                    put("opening_hours", before.obj("opening_hours"))
                    put("time_zone", before.str("time_zone"))
                },
            )
        }
    }

    private fun updateRestaurant(
        current: JsonObject,
        change: JsonObject,
    ) = api.expect(OK, "POST", "/v1/staff/restaurant/update", change, a.manager, ifMatch(current.int("version"))).json

    private fun credentials(
        email: String,
        password: String,
    ) = buildJsonObject {
        put("email", email)
        put("password", password)
    }

    private companion object {
        const val OTHER_ZONE = "Asia/Bangkok"
    }
}
