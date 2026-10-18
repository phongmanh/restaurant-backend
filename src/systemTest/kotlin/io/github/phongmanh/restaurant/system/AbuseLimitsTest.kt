package io.github.phongmanh.restaurant.system

import io.github.phongmanh.restaurant.core.auth.crypto.Totp
import io.github.phongmanh.restaurant.staging.StagingApi
import io.github.phongmanh.restaurant.staging.base32Decode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * test-plan §4 ST-14 (NFR-SEC-04): the limits on staging, with the real Upstash store and the real
 * WAF rule (deploy/README.md step 8), and an admin refused without the TOTP step. CI proves every
 * policy's numbers against a fake store (IT-AUTH-13 and the features' tests); this run proves the
 * deployed pieces count: one limit per kind of subject (an IP, a staff member), and the WAF.
 *
 * The limits are fixed windows aligned to the minute (core-and-auth §11), so each burst starts at
 * the top of a minute and fits inside it. The WAF rule is 300 a minute per IP, as §11 sets it (Q-20;
 * it was 60 until 2026-10-18, which kept `auth.refresh`'s 120 out of reach). While a window is spent, the other tests' calls wait
 * it out ([StagingApi] retries a `429`).
 */
class AbuseLimitsTest {
    private val api = Staging.api

    @Test
    fun `ST-14 past 30 sign-ins a minute from one IP the app answers 429, and past the WAF rule the edge does`() {
        topOfMinute()
        val statuses = mutableListOf<String>()
        var appLimit: StagingApi.Response? = null
        var edge: StagingApi.Response? = null
        var spoofed: StagingApi.Response? = null
        for (i in 1..SIGN_IN_BURST) {
            val response = api.call("POST", "/v1/auth/sign-in", unknownAccount(i), waitOut429 = false)
            statuses += if (response.fromEdge) "edge ${response.status}" else "${response.status}"
            if (response.status == StagingApi.TOO_MANY_REQUESTS && !response.fromEdge && appLimit == null) {
                appLimit = response
                // The limit counts by the IP Vercel's edge sets (ASVS 5.0 V4.1.3, V15.3.4): headers the
                // client sends naming another IP must not open a fresh allowance.
                spoofed = api.call("POST", "/v1/auth/sign-in", unknownAccount(0), headers = SPOOFED_IP, waitOut429 = false)
            }
            if (response.fromEdge) {
                edge = response
                break
            }
        }
        val counts = statuses.groupingBy { it }.eachCount()
        println("[ST-14] sign-in burst: $counts in ${statuses.size} requests")
        assertEquals(SIGN_IN_LIMIT, statuses.takeWhile { it == "$UNAUTHORIZED" }.size, "the first 30 are answered: $counts")

        val limited = requireNotNull(appLimit) { "the app never answered 429: $counts" }
        assertEquals("rate_limited", limited.code)
        val retryAfter = requireNotNull(limited.header("Retry-After")) { "the app's 429 carries Retry-After" }.toLong()
        assertTrue(retryAfter in 1..WINDOW_S, "Retry-After is the rest of the minute: $retryAfter")
        val spoof = requireNotNull(spoofed)
        assertEquals(
            StagingApi.TOO_MANY_REQUESTS to "rate_limited",
            spoof.status to spoof.code,
            "X-Real-IP and X-Forwarded-For from the client don't change the IP the limit counts",
        )

        val waf = requireNotNull(edge) { "the edge never answered 429 in $SIGN_IN_BURST requests: is the WAF rule on? $counts" }
        assertEquals(StagingApi.TOO_MANY_REQUESTS, waf.status, "the WAF's answer")
        println(
            "[ST-14] the WAF answered after ${statuses.size} requests (rule: 300 per 60 s per IP); a spoofed client IP was still limited",
        )
        waitForNextMinute()
    }

    @Test
    fun `ST-14 past 120 refreshes a minute from one IP the app answers 429`() {
        topOfMinute()
        val body = buildJsonObject { put("audience", "staff") }
        val statuses = (1..REFRESH_LIMIT + 1).map { api.call("POST", "/v1/auth/refresh", body, waitOut429 = false) }
        val counts = statuses.map { if (it.fromEdge) "edge ${it.status}" else "${it.status}" }.groupingBy { it }.eachCount()
        assertTrue(statuses.take(REFRESH_LIMIT).all { it.status == UNAUTHORIZED }, "the first 120 are answered (no cookie: 401): $counts")
        val last = statuses.last()
        assertEquals(StagingApi.TOO_MANY_REQUESTS to "rate_limited", last.status to last.code, "the 121st is the app's 429: $counts")
        assertTrue(last.header("Retry-After") != null, "with Retry-After")
        println("[ST-14] refresh burst: $counts")
        waitForNextMinute()
    }

    @Test
    fun `ST-14 past 30 report reads a minute a staff member gets 429, counted per staff member`() {
        val b = Staging.b
        val manager = b.manager
        val today = LocalDate.now(ZoneOffset.UTC)
        val path = "/v1/staff/reports/daily-sales?from=${today.minusDays(6)}&to=$today"
        topOfMinute()
        val statuses = (1..REPORT_LIMIT + 1).map { api.call("GET", path, token = manager, waitOut429 = false) }
        assertTrue(statuses.take(REPORT_LIMIT).all { it.status == OK }, "the first 30 are served: ${statuses.map { it.status }}")
        val last = statuses.last()
        assertEquals(StagingApi.TOO_MANY_REQUESTS to "rate_limited", last.status to last.code, "the 31st is the app's 429")
        assertTrue(last.header("Retry-After") != null, "with Retry-After")

        val other = api.call("GET", path, token = Staging.a.manager, waitOut429 = false)
        assertEquals(OK, other.status, "another staff member is still served: the limit is per staff member")
        println("[ST-14] report reads: 30 served, the 31st refused with Retry-After ${last.header("Retry-After")} s")
    }

    @Test
    fun `ST-14 an admin's password alone opens nothing, and a wrong TOTP code uses up the challenge`() {
        val admin = Staging.accounts.admin
        val first = api.expect(OK, "POST", "/v1/auth/sign-in", credentials(admin.email, admin.password)).json
        assertEquals(JsonPrimitive(true), first["mfa_required"], "the password answers a TOTP challenge")
        assertNull(first["access_token"], "and no access token")
        val challenge = first.str("mfa_token")

        val asBearer = api.call("GET", "/v1/admin/restaurants", token = challenge)
        assertEquals(UNAUTHORIZED, asBearer.status, "the challenge isn't an access token")

        val wrong = api.call("POST", "/v1/auth/mfa", mfa(challenge, wrongCode(admin.totpSecret)))
        assertEquals(UNAUTHORIZED to "invalid_credentials", wrong.status to wrong.code, "a wrong code is refused")
        val again = api.call("POST", "/v1/auth/mfa", mfa(challenge, Totp.code(base32Decode(admin.totpSecret), step())))
        assertEquals(UNAUTHORIZED to "invalid_credentials", again.status to again.code, "the challenge is used up: sign in again")

        // A finished sign-in clears the failure the wrong code counted (core-and-auth §8.1).
        api.expect(OK, "GET", "/v1/admin/restaurants", token = Staging.adminToken())
    }

    private fun unknownAccount(i: Int) = credentials("st14.$i.${Staging.run}@${Staging.DOMAIN}", "not-the-password")

    private fun credentials(
        email: String,
        password: String,
    ) = buildJsonObject {
        put("email", email)
        put("password", password)
    }

    private fun mfa(
        token: String,
        code: String,
    ) = buildJsonObject {
        put("mfa_token", token)
        put("code", code)
    }

    private fun step() = Totp.step(Instant.now().epochSecond)

    /** A code that matches none of the steps the service accepts (±1). */
    private fun wrongCode(secret: String): String {
        val valid = (step() - 1..step() + 1).map { Totp.code(base32Decode(secret), it) }.toSet()
        return listOf("000000", "111111", "222222").first { it !in valid }
    }

    /** Waits until a fresh minute has begun, unless this one has just begun. */
    private fun topOfMinute() {
        val second = Instant.now().epochSecond % WINDOW_S
        if (second > START_SLACK_S) Thread.sleep((WINDOW_S - second + 1) * MILLIS)
    }

    /** So the tests after this one find the sign-in window fresh (the WAF's included). */
    private fun waitForNextMinute() = Thread.sleep((WINDOW_S - Instant.now().epochSecond % WINDOW_S + 1) * MILLIS)

    private companion object {
        const val SIGN_IN_LIMIT = 30
        const val REPORT_LIMIT = 30

        const val REFRESH_LIMIT = 120

        /** Past the WAF's 300 a minute, with room for a WAF window that doesn't start on the minute. */
        const val SIGN_IN_BURST = 610

        /** Documentation addresses (RFC 5737), never the caller's. */
        val SPOOFED_IP = mapOf("X-Real-IP" to "203.0.113.7", "X-Forwarded-For" to "198.51.100.9")
        const val WINDOW_S = 60L
        const val START_SLACK_S = 5L
        const val MILLIS = 1_000L
    }
}
