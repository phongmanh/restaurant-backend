package io.github.phongmanh.restaurant.staging

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.util.UUID

/** A guest's phone at a table, for the load runs: the token from one scan and the table's QR token. */
class LoadGuest(
    val token: String,
    val sessionId: String,
    val qrToken: String,
)

/**
 * A seeded restaurant (test-plan §6) as the load runs use it (§9 step 6b): its staff sign in, its
 * guests scan their tables' codes, and the run closes their sessions when it ends, as the system
 * tests' scenarios do.
 */
class LoadSite(
    private val api: StagingApi,
    val seed: SeededRestaurant,
) {
    private val flows = StaffFlows(api)

    fun signIn(login: Login): String =
        flows.signIn(
            login.email,
            requireNotNull(login.password) { "the accounts file has no password for ${seed.name}'s ${login.staffId}" },
        )

    fun tables(token: String): List<JsonObject> = api.expect(OK, "GET", "/v1/staff/tables", token = token).json.objects("items")

    /** The enabled tables without guests. */
    fun freeTables(token: String): List<JsonObject> = tables(token).filter { it.flag("enabled") && it.text("state") == "free" }

    /** Closes every session still open in the restaurant, as a run that stopped half-way leaves them. */
    fun closeOpenSessions(token: String): Int {
        val open = tables(token).filter { it.text("state") == "occupied" }
        open.forEach { close(it.obj("session").text("id"), token) }
        return open.size
    }

    /** The token in a table's printed QR link (`<customer app>/t/<token>`). */
    fun qrToken(
        tableId: String,
        token: String,
    ): String =
        api
            .expect(OK, "GET", "/v1/staff/tables/$tableId/qr-code", token = token)
            .json
            .text("url")
            .substringAfter("/t/")

    /** A guest scans the code with a phone of their own: it opens the table's session or joins it. */
    fun scan(qrToken: String): LoadGuest {
        val body =
            buildJsonObject {
                put("qr_token", qrToken)
                put("device_id", UUID.randomUUID().toString())
            }
        val answer = api.expect(OK, "POST", "/v1/guest/sessions", body).json
        return LoadGuest(answer.text("session_token"), answer.obj("session").text("id"), qrToken)
    }

    /** Up to [max] items a guest can order as they are: shown, not sold out, without options. */
    fun orderableItems(
        guestToken: String,
        max: Int,
    ): List<String> =
        api
            .expect(OK, "GET", "/v1/guest/menu", token = guestToken)
            .json
            .objects("categories")
            .flatMap { it.objects("items") }
            .filter { !it.flag("sold_out") && it.array("option_groups").isEmpty() }
            .map { it.text("id") }
            .take(max)

    /**
     * Every order of a session in any status, page by page. The list's `status` defaults to the open
     * ones (`placed,accepted,preparing,ready`), so it's named in full: without it the smoke run of
     * 2026-10-18 counted every served order as lost.
     */
    fun orderIds(
        sessionId: String,
        token: String,
    ): Set<String> {
        val ids = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = cursor?.let { "&cursor=" + URLEncoder.encode(it, Charsets.UTF_8) }.orEmpty()
            val path = "/v1/staff/orders?session_id=$sessionId&status=$EVERY_STATUS&limit=$PAGE$page"
            val answer = api.expect(OK, "GET", path, token = token).json
            answer.objects("items").mapTo(ids) { it.text("id") }
            cursor = answer["next_cursor"]?.jsonPrimitive?.contentOrNull
        } while (cursor != null)
        return ids
    }

    /**
     * Closes a session as a waiter would, with `force`: the close cancels the orders that weren't
     * served (orders §7.1), and the served ones are paid in cash (the system tests' `Site.closeSession`).
     */
    fun close(
        sessionId: String,
        token: String,
    ) {
        val bill = api.expect(OK, "GET", "/v1/staff/table-sessions/$sessionId/bill", token = token).json
        val served =
            api
                .expect(OK, "GET", "/v1/staff/orders?session_id=$sessionId&status=served&limit=$PAGE", token = token)
                .json
                .objects("items")
                .sumOf { it.obj("total").text("amount").toLong() }
        val body =
            buildJsonObject {
                if (served > 0) {
                    putJsonObject("payment") {
                        put("method", "cash")
                        putJsonObject("amount_received") {
                            put("amount", served.toString())
                            put("currency", "VND")
                        }
                    }
                }
                put("force", true)
            }
        val headers = mapOf("If-Match" to "\"${bill.text("version")}\"", "Idempotency-Key" to UUID.randomUUID().toString())
        api.expect(OK, "POST", "/v1/staff/table-sessions/$sessionId/close", body, token, headers)
    }

    companion object {
        const val OK = 200
        const val CREATED = 201

        /** OrderStatus's values in contract 1.1.0. */
        private const val EVERY_STATUS = "placed,accepted,preparing,ready,served,rejected,cancelled"

        /** The list's largest page (the contract's `limit` maximum). */
        private const val PAGE = 200
    }
}

private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content

private fun JsonObject.flag(field: String): Boolean = text(field).toBooleanStrict()

private fun JsonObject.obj(field: String): JsonObject = getValue(field).jsonObject

private fun JsonObject.array(field: String): JsonArray = getValue(field).jsonArray

private fun JsonObject.objects(field: String): List<JsonObject> = array(field).map { it.jsonObject }
