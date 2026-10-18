package io.github.phongmanh.restaurant.staging

import io.github.phongmanh.restaurant.staging.StaffFlows.Companion.CREATED
import io.github.phongmanh.restaurant.staging.StaffFlows.Companion.OK
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.PrintStream
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * ST-11's restaurant, `Z <run> (ZAP)`: onboarded through the API before the scan, with a waiter, a
 * menu item, a table, a guest session, an order and a waiter call, so the scan's writes land there
 * and not in A or B, and the scan reaches real rows instead of stopping at `404`. About 15
 * requests; with the clean-up, the run's own client stops at [SETUP] + [CLEANUP].
 */
class ZapSite(
    private val api: StagingApi,
    private val run: String,
    private val out: PrintStream,
) {
    private val flows = StaffFlows(api)
    private val random = SecureRandom()

    /** The IDs the path examples get, by parameter name, and a fresh token per profile when it starts. */
    class Z(
        val ids: Map<String, String>,
        private val tokens: Map<ZapProfile, () -> String?>,
    ) {
        fun token(profile: ZapProfile): String? = tokens.getValue(profile)()
    }

    fun setUp(): Z {
        val accounts = Accounts.read(File(Accounts.PATH))
        val totp = Authenticator(accounts.admin.totpSecret)
        val adminToken = { flows.signIn(accounts.admin.email, accounts.admin.password, totp) }
        val (restaurantId, managerToken) = onboard(adminToken())
        val token = managerToken()
        val ids = mutableMapOf("restaurantId" to restaurantId)
        ids["staffId"] = inviteWaiter(token)
        ids["categoryId"] =
            create("/v1/staff/menu/categories", token) {
                put("name_vi", "Món chính")
                put("name_en", "Mains")
                put("active", true)
            }
        ids["itemId"] = create("/v1/staff/menu/items", token) { menuItem(ids.getValue("categoryId")) }
        ids["tableId"] =
            create("/v1/staff/tables", token) {
                put("number", "1")
                put("area", "Trong nhà")
                put("seats", TABLE_SEATS)
            }
        val qrToken = api.expect(OK, "GET", "/v1/staff/tables/${ids["tableId"]}/qr-code", token = token).text("url").substringAfter("/t/")
        val guestToken = seatGuest(qrToken, ids)
        order(guestToken, ids)
        ids["callId"] = create("/v1/guest/waiter-calls", guestToken) { put("kind", "call_waiter") }
        check(ids.keys == ZapPlan.PATH_EXAMPLES.values.toSet()) { "the setup and ZapPlan.PATH_EXAMPLES name different path parameters" }
        // ASVS 5.0 V13.4.4; ZAP 2.17's stable image has no rule for HTTP methods.
        val trace = api.call("TRACE", "/v1/auth/me", token = token)
        out.println("Restaurant Z for the scan: $restaurantId (${api.sent} requests). TRACE /v1/auth/me answered ${trace.status}.")
        val tokens =
            mapOf(
                ZapProfile.GUEST to { guestToken },
                ZapProfile.STAFF to managerToken,
                ZapProfile.FLOOR to managerToken,
                ZapProfile.ADMIN to adminToken,
                ZapProfile.ANONYMOUS to { null },
            )
        return Z(ids, tokens)
    }

    /** Z and its first manager, who sets a password through the invitation; returns Z's ID and a way to sign in. */
    private fun onboard(adminToken: String): Pair<String, () -> String> {
        val manager = "manager.z.$run@$DOMAIN"
        val body =
            buildJsonObject {
                put("name", "Nhà hàng Z $run (ZAP)")
                put("address", "Z Lê Lợi, Quận 1, TP. Hồ Chí Minh")
                put("time_zone", "Asia/Ho_Chi_Minh")
                putJsonArray("languages") { add(JsonPrimitive("vi")) }
                putJsonObject("first_manager") {
                    put("email", manager)
                    put("display_name", "Quản lý Z")
                }
            }
        val onboarded =
            api
                .expect(
                    CREATED,
                    "POST",
                    "/v1/admin/restaurants",
                    body,
                    adminToken,
                    mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
                ).json
        val password = newPassword(random)
        flows.acceptInvitation(flows.tokenOf(onboarded.obj("invitation")), password)
        return onboarded.obj("restaurant").text("id") to { flows.signIn(manager, password) }
    }

    private fun inviteWaiter(token: String): String {
        val body =
            buildJsonObject {
                put("email", "waiter.z.$run@$DOMAIN")
                put("display_name", "Phục vụ Z")
                put("role", "waiter")
            }
        val waiter = api.expect(CREATED, "POST", "/v1/staff/members", body, token).json
        flows.acceptInvitation(flows.tokenOf(waiter.obj("invitation")), newPassword(random))
        return waiter.obj("member").text("id")
    }

    private fun JsonObjectBuilder.menuItem(categoryId: String) {
        put("category_id", categoryId)
        put("name_vi", "Phở bò")
        put("name_en", "Beef pho")
        putJsonObject("price") {
            put("amount", "55000")
            put("currency", "VND")
        }
        put("active", true)
    }

    /** A guest scans Z's table; returns their session token. */
    private fun seatGuest(
        qrToken: String,
        ids: MutableMap<String, String>,
    ): String {
        val body =
            buildJsonObject {
                put("qr_token", qrToken)
                put("device_id", UUID.randomUUID().toString())
            }
        val guest = api.expect(OK, "POST", "/v1/guest/sessions", body).json
        ids["sessionId"] = guest.obj("session").text("id")
        return guest.text("session_token")
    }

    private fun order(
        guestToken: String,
        ids: MutableMap<String, String>,
    ) {
        val body =
            buildJsonObject {
                putJsonArray("lines") {
                    add(
                        buildJsonObject {
                            put("item_id", ids.getValue("itemId"))
                            put("quantity", 1)
                        },
                    )
                }
            }
        val order =
            api
                .expect(
                    CREATED,
                    "POST",
                    "/v1/guest/orders",
                    body,
                    guestToken,
                    mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
                ).json
        ids["orderId"] = order.text("id")
        ids["lineId"] =
            order
                .getValue("lines")
                .jsonArray[0]
                .jsonObject
                .text("id")
    }

    private fun create(
        path: String,
        token: String,
        body: JsonObjectBuilder.() -> Unit,
    ): String = api.expect(CREATED, "POST", path, buildJsonObject(body), token).text("id")

    /**
     * The admin profile's writes can leave admins and restaurants behind, with invitations only ZAP
     * saw: this deactivates every admin and suspends every restaurant made since the run [started],
     * Z included. A UUID v7 holds its creation time, so a restaurant's ID dates it.
     */
    fun cleanUp(
        adminToken: String,
        started: Instant,
    ) {
        val admins =
            pages("/v1/admin/admins", adminToken).filter {
                Instant.parse(it.text("created_at")) >= started && it.text("status") != "deactivated"
            }
        admins.forEach { api.expect(OK, "POST", "/v1/admin/admins/${it.text("id")}/deactivate", token = adminToken) }
        val made =
            pages("/v1/admin/restaurants", adminToken).filter {
                Instant.ofEpochMilli(UUID.fromString(it.text("id")).mostSignificantBits ushr UUID_V7_TIME_SHIFT) >= started
            }
        val active = made.filter { it.text("status") == "active" }
        val reason = buildJsonObject { put("reason", "ZAP scan $run") }
        active.forEach { api.expect(OK, "POST", "/v1/admin/restaurants/${it.text("id")}/suspend", reason, adminToken) }
        out.println(
            "Clean-up: ${admins.size} admins deactivated; ${made.size} restaurants made by this run (Z included), " +
                "${active.size} suspended now, the rest already suspended by the scan.",
        )
    }

    private fun pages(
        path: String,
        token: String,
    ): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var cursor: String? = null
        do {
            val query = (if ('?' in path) "&" else "?") + "limit=$PAGE" + cursor?.let { "&cursor=$it" }.orEmpty()
            val page = api.expect(OK, "GET", path + query, token = token).json
            items += page.getValue("items").jsonArray.map { it.jsonObject }
            cursor = page["next_cursor"]?.jsonPrimitive?.contentOrNull
        } while (cursor != null)
        return items
    }

    companion object {
        const val SETUP = 60
        const val CLEANUP = 300
        private const val DOMAIN = "staging.example"
        private const val TABLE_SEATS = 4
        private const val PAGE = 200
        private const val UUID_V7_TIME_SHIFT = 16
    }
}

private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content

private fun JsonObject.obj(field: String): JsonObject = getValue(field).jsonObject
