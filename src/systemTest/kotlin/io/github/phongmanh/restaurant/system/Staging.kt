package io.github.phongmanh.restaurant.system

import io.github.phongmanh.restaurant.staging.Accounts
import io.github.phongmanh.restaurant.staging.Authenticator
import io.github.phongmanh.restaurant.staging.Login
import io.github.phongmanh.restaurant.staging.SeededRestaurant
import io.github.phongmanh.restaurant.staging.StaffFlows
import io.github.phongmanh.restaurant.staging.StagingApi
import io.github.phongmanh.restaurant.staging.env
import io.github.phongmanh.restaurant.support.ContractCheck
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One run of the system tests against a staging deployment (test-plan §2, §5, §9 step 5):
 *
 *     ./gradlew --no-daemon systemTest -PstagingUrl=https://restaurant-staging-….vercel.app
 *
 * with the environment exported from both env files, as for `seedStaging` (deploy/README.md). It
 * reads the seeded accounts from [Accounts.PATH]. Every response is checked against the contract
 * with CI's own [ContractCheck], so a response that breaks it fails the test that got it. The run
 * stops at [BUDGET] requests (test-plan §9's budget guard) and prints what it sent when it ends.
 *
 * Each scenario seats its guests at tables that are free when it starts and closes their sessions
 * when it ends, so a run leaves the restaurants as it found them, bar the closed bills, the
 * accounts it made and the restaurant AT-040 onboards.
 */
object Staging {
    const val BUDGET = 2_000
    private const val PLANNED = 900
    const val DOMAIN = "staging.example"

    /** Signs in again before a token's 15 minutes run out (architecture §7). */
    private val TOKEN_REUSE: Duration = Duration.ofMinutes(10)

    val url: String =
        System.getProperty("staging.url").orEmpty().also {
            require(it.startsWith("https://")) { "run as ./gradlew systemTest -PstagingUrl=<deployment URL> (deploy/README.md)" }
        }

    /** Names this run's accounts, tables and menu entries apart from earlier runs'. */
    val run: String = java.lang.Long.toString(Instant.now().epochSecond, Character.MAX_RADIX)

    val api = StagingApi(url, env("VERCEL_AUTOMATION_BYPASS_SECRET"), BUDGET, System.out, ::checkContract)
    val flows = StaffFlows(api)
    val accounts: Accounts = Accounts.read(File(Accounts.PATH))
    val a = Site(accounts.restaurants.getValue("A"))
    val b = Site(accounts.restaurants.getValue("B"))

    /** The customer app's QR links (`deploy/staging/staging.env`). */
    val customerAppUrl: String = env("CUSTOMER_APP_URL")

    private val tokens = ConcurrentHashMap<String, Pair<String, Instant>>()
    private val admin by lazy { Authenticator(accounts.admin.totpSecret) }

    init {
        println("System tests on $url, run $run: about $PLANNED requests, at most $BUDGET (test-plan §9).")
        Runtime.getRuntime().addShutdownHook(Thread { println("System tests sent ${api.sent} requests (run $run).") })
    }

    /** A staff member's access token, signed in once per run (and again after [TOKEN_REUSE]). */
    fun token(login: Login): String = cached(login.email) { flows.signIn(login.email, requireNotNull(login.password)) }

    /** The platform admin's token, through the TOTP step. */
    fun adminToken(): String = cached(accounts.admin.email) { flows.signIn(accounts.admin.email, accounts.admin.password, admin) }

    private fun cached(
        key: String,
        signIn: () -> String,
    ): String {
        val now = Instant.now()
        tokens[key]?.let { (token, at) -> if (Duration.between(at, now) < TOKEN_REUSE) return token }
        return signIn().also { tokens[key] = it to now }
    }

    private fun checkContract(exchange: StagingApi.Exchange) {
        ContractCheck.check(exchange.method, exchange.path, exchange.status, exchange.contentType, exchange.body)?.let {
            throw AssertionError("the response broke docs/api/openapi.yaml: $it")
        }
    }
}

/** A seeded restaurant (test-plan §6) and what its staff can do through the API. */
class Site(
    val seed: SeededRestaurant,
) {
    val id: String get() = seed.id
    val manager: String get() = Staging.token(seed.manager)
    val waiter: String get() = Staging.token(seed.waiters[0])
    val waiter2: String get() = Staging.token(seed.waiters[1])
    val kitchen: String get() = Staging.token(requireNotNull(seed.kitchen))

    /** The staff menu, every item included. */
    fun staffMenu(): JsonObject = Staging.api.expect(OK, "GET", "/v1/staff/menu", token = manager).json

    /** A menu item by its English name, as the staff menu shows it. */
    fun item(nameEn: String): JsonObject =
        staffMenu()
            .arr("categories")
            .flatMap { it.jsonObject["items"]?.jsonArray.orEmpty() }
            .map { it.jsonObject }
            .first { it.str("name_en") == nameEn }

    fun tables(): List<JsonObject> = Staging.api.expect(OK, "GET", "/v1/staff/tables", token = manager).arrOf("items")

    /** [count] enabled tables without guests; the scenario's guests sit there. */
    fun freeTables(count: Int): List<JsonObject> {
        val free = tables().filter { it.str("state") == "free" && it.bool("enabled") }
        check(free.size >= count) { "restaurant ${seed.name} has ${free.size} free tables, the scenario needs $count" }
        return free.take(count)
    }

    /** The token in a table's printed QR link (`<customer app>/t/<token>`). */
    fun qrToken(tableId: String): String =
        Staging.api
            .expect(OK, "GET", "/v1/staff/tables/$tableId/qr-code", token = manager)
            .text("url")
            .substringAfter("/t/")

    /** A guest scans the table's code with a phone of their own. */
    fun seat(tableId: String): Guest = Guest.scan(qrToken(tableId))

    /**
     * Runs [block] with [count] free tables, then closes any session still open on them (FR-013's
     * close, with `force`), so the next scenario finds them free.
     */
    fun <T> withTables(
        count: Int,
        block: (List<JsonObject>) -> T,
    ): T {
        val tables = freeTables(count)
        try {
            return block(tables)
        } finally {
            val ids = tables.map { it.str("id") }.toSet()
            tables().filter { it.str("id") in ids && it.str("state") == "occupied" }.forEach {
                closeSession(it.obj("session").str("id"))
            }
        }
    }

    /**
     * Closes a session as a waiter would, with `force`: the close cancels the orders that weren't
     * served (orders §7.1), so what's paid, in [method], is the served orders' total.
     */
    fun closeSession(
        sessionId: String,
        method: String = "cash",
        amountReceived: Long? = null,
    ): JsonObject {
        val bill = Staging.api.expect(OK, "GET", "/v1/staff/table-sessions/$sessionId/bill", token = waiter).json
        val total =
            Staging.api
                .expect(OK, "GET", "/v1/staff/orders?session_id=$sessionId&status=served&limit=200", token = waiter)
                .arrOf("items")
                .sumOf { it.money("total") }
        val body =
            buildJsonObject {
                if (total > 0) {
                    putJsonObject("payment") {
                        put("method", method)
                        put("amount_received", vnd(amountReceived ?: total))
                    }
                }
                put("force", true)
            }
        return Staging.api
            .expect(
                OK,
                "POST",
                "/v1/staff/table-sessions/$sessionId/close",
                body,
                waiter,
                ifMatch(bill.int("version")) + idempotencyKey(),
            ).json
    }

    /** Moves an order on as [token]'s role may (`accepted`, `preparing`, …); returns the order. */
    fun step(
        order: JsonObject,
        status: String,
        token: String = kitchen,
    ): JsonObject =
        Staging.api
            .expect(
                OK,
                "POST",
                "/v1/staff/orders/${order.str("id")}/status",
                buildJsonObject { put("status", status) },
                token,
                ifMatch(order.int("version")),
            ).json

    /** The kitchen's steps and then the waiter's, from wherever [order] is, up to `served`. */
    fun serve(order: JsonObject): JsonObject {
        var current = order
        for (status in KITCHEN_STEPS.drop(KITCHEN_STEPS.indexOf(current.str("status")) + 1)) current = step(current, status)
        return step(current, "served", waiter)
    }

    /** Invites a member and sets their password; returns their login. */
    fun invite(
        tag: String,
        role: String,
    ): Login {
        val email = "$tag.${Staging.run}@${Staging.DOMAIN}"
        val invited =
            Staging.api
                .expect(
                    CREATED,
                    "POST",
                    "/v1/staff/members",
                    buildJsonObject {
                        put("email", email)
                        put("display_name", "System test $tag")
                        put("role", role)
                    },
                    manager,
                ).json
        val password = UUID.randomUUID().toString()
        Staging.flows.acceptInvitation(Staging.flows.tokenOf(invited.obj("invitation")), password)
        return Login(email, password, invited.obj("member").str("id"))
    }
}

private val KITCHEN_STEPS = listOf("placed", "accepted", "preparing", "ready")

/** A guest's phone: the session token from one scan (tables §3). */
class Guest(
    val token: String,
    val session: JsonObject,
) {
    val sessionId: String get() = session.str("id")
    val tableId: String get() = session.obj("table").str("id")

    fun call(
        method: String,
        path: String,
        body: JsonElement? = null,
        headers: Map<String, String> = emptyMap(),
    ) = Staging.api.call(method, path, body, token, headers)

    fun expect(
        status: Int,
        method: String,
        path: String,
        body: JsonElement? = null,
        headers: Map<String, String> = emptyMap(),
    ) = Staging.api.expect(status, method, path, body, token, headers)

    /** Places an order; [key] is the app's `Idempotency-Key`, kept for its retries. */
    fun order(
        vararg lines: JsonObject,
        key: String = UUID.randomUUID().toString(),
    ): JsonObject = expect(CREATED, "POST", "/v1/guest/orders", orderBody(*lines), mapOf("Idempotency-Key" to key)).json

    fun bill(): JsonObject = expect(OK, "GET", "/v1/guest/bill").json

    fun orders(): List<JsonObject> = expect(OK, "GET", "/v1/guest/orders").arrOf("items")

    companion object {
        /** A scan from a new device. */
        fun scan(qrToken: String): Guest {
            val response =
                Staging.api
                    .expect(
                        OK,
                        "POST",
                        "/v1/guest/sessions",
                        buildJsonObject {
                            put("qr_token", qrToken)
                            put("device_id", UUID.randomUUID().toString())
                        },
                    ).json
            return Guest(response.str("session_token"), response.obj("session"))
        }
    }
}

const val OK = 200
const val CREATED = 201
const val NO_CONTENT = 204
const val UNAUTHORIZED = 401
const val FORBIDDEN = 403
const val NOT_FOUND = 404
const val CONFLICT = 409
const val PRECONDITION_FAILED = 412

fun orderBody(vararg lines: JsonObject) = buildJsonObject { putJsonArray("lines") { lines.forEach { add(it) } } }

fun line(
    itemId: String,
    quantity: Int = 1,
    note: String? = null,
    options: List<String> = emptyList(),
) = buildJsonObject {
    put("item_id", itemId)
    put("quantity", quantity)
    if (options.isNotEmpty()) putJsonArray("option_ids") { options.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    note?.let { put("note", it) }
}

fun vnd(amount: Long) =
    buildJsonObject {
        put("amount", amount.toString())
        put("currency", "VND")
    }

fun ifMatch(version: Int) = mapOf("If-Match" to "\"$version\"")

fun idempotencyKey() = mapOf("Idempotency-Key" to UUID.randomUUID().toString())

fun JsonObject.str(field: String): String = getValue(field).jsonPrimitive.content

fun JsonObject.int(field: String): Int = str(field).toInt()

fun JsonObject.bool(field: String): Boolean = str(field).toBooleanStrict()

fun JsonObject.obj(field: String): JsonObject = getValue(field).jsonObject

fun JsonObject.arr(field: String): JsonArray = getValue(field).jsonArray

fun JsonObject.arrOf(field: String): List<JsonObject> = arr(field).map { it.jsonObject }

/** A `Money` field in whole đồng. */
fun JsonObject.money(field: String): Long = obj(field).str("amount").toLong()

fun StagingApi.Response.arrOf(field: String): List<JsonObject> = json.arrOf(field)

/** The problem's stable `code`, for a refused call. */
val StagingApi.Response.code: String? get() = (body as? JsonObject)?.get("code")?.jsonPrimitive?.content
