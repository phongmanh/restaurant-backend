package io.github.phongmanh.restaurant.support

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The contract check (G4 hardening H3) refuses what it should, so a passing suite means something. */
class ContractCheckTest {
    private val problemJson = "application/problem+json"
    private val orderPath = "/v1/staff/orders/0192f0a4-0000-7000-8000-000000000001"

    private fun problem(extra: String = "") =
        """{"type":"/problems/not_found","title":"Not found","status":404,"code":"not_found","request_id":"r1"$extra}"""

    private val restaurant =
        """
        "id":"0192f0a4-0000-7000-8000-000000000002","name":"Phở 24","address":"1 Lê Lợi","time_zone":"Asia/Ho_Chi_Minh",
        "currency":"VND","languages":["vi"],"opening_hours":{},"status":"active","version":1
        """.trimIndent()

    private fun violation(
        method: String,
        path: String,
        status: Int,
        contentType: String?,
        body: String?,
    ) = assertNotNull(ContractCheck.check(method, path, status, contentType, body))

    @Test
    fun `a response that keeps the contract passes`() {
        assertNull(ContractCheck.check("GET", orderPath, 404, problemJson, problem()))
        assertNull(ContractCheck.check("GET", "/v1/staff/restaurant", 200, "application/json", "{$restaurant}"))
    }

    @Test
    fun `a path or method the contract doesn't have isn't checked`() {
        assertNull(ContractCheck.check("GET", "/health", 200, "application/json", "{}"))
        assertNull(ContractCheck.check("GET", "/v1/staff/test-whoami", 418, "text/plain", "x"))
    }

    @Test
    fun `an undocumented status fails`() {
        assertContains(violation("GET", orderPath, 418, problemJson, problem()), "status not documented")
    }

    @Test
    fun `a shared code from core-and-auth §3 passes undocumented, as a problem with its code`() {
        val internal = problem().replace("404", "500").replace("not_found", "internal_error")
        assertNull(ContractCheck.check("GET", orderPath, 500, problemJson, internal))
        assertContains(violation("GET", orderPath, 500, problemJson, problem().replace("404", "500")), "code isn't internal_error")
        assertContains(violation("GET", orderPath, 500, "text/plain", "oops"), "media type")
        // 415 needs a request body: a GET can't answer it.
        val unsupported = problem().replace("404", "415").replace("not_found", "unsupported_media_type")
        assertContains(violation("GET", orderPath, 415, problemJson, unsupported), "status not documented")
        assertNull(ContractCheck.check("POST", "/v1/staff/tables", 415, problemJson, unsupported))
    }

    @Test
    fun `a wrong media type, or a body where the contract has none, fails`() {
        assertContains(violation("GET", orderPath, 404, "application/json", problem()), "media type")
        assertContains(violation("POST", "/v1/auth/sign-out", 204, "application/json", "{}"), "no body")
    }

    @Test
    fun `a body that doesn't match the schema fails`() {
        violation("GET", orderPath, 404, problemJson, problem().replace("\"status\":404", "\"status\":\"404\""))
        violation("GET", orderPath, 404, problemJson, problem().replace(",\"code\":\"not_found\"", ""))
        violation("GET", orderPath, 404, problemJson, "not json")
    }

    @Test
    fun `a field with the wrong format fails`() {
        violation(
            "GET",
            "/v1/staff/restaurant",
            200,
            "application/json",
            "{${restaurant.replace("0192f0a4-0000-7000-8000-000000000002", "42")}}",
        )
    }

    @Test
    fun `a field the contract doesn't name fails, nested and inside an allOf too`() {
        violation("GET", orderPath, 404, problemJson, problem(""","internal":"x""""))
        assertNull(ContractCheck.check("GET", orderPath, 404, problemJson, problem(""","errors":[{"field":"/a","code":"required"}]""")))
        violation("GET", orderPath, 404, problemJson, problem(""","errors":[{"field":"/a","code":"required","value":"x"}]"""))
        val admin = "/v1/admin/restaurants/0192f0a4-0000-7000-8000-000000000002"
        val created = ""","created_at":"2026-10-17T00:00:00Z""""
        // Fields from both members of AdminRestaurant's allOf pass; one from neither fails.
        assertNull(ContractCheck.check("GET", admin, 200, "application/json", "{$restaurant$created}"))
        violation("GET", admin, 200, "application/json", """{$restaurant$created,"password_hash":"x"}""")
    }
}
