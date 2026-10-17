package io.github.phongmanh.restaurant.system

import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * test-plan §4 ST-18 (NFR-OBS-01), the request ID's half: a client's request is found by its ID in
 * the response, the problem body and Vercel's logs (core-and-auth §2). This test makes the calls
 * and writes the IDs to [TRACE]; `deploy/staging/log-review.py` then finds them in the logs.
 */
class RequestIdTest {
    private val api = Staging.api

    @Test
    fun `ST-18 the service answers its own request ID, in the header and the problem, and keeps the client's apart`() {
        val clientId = "st18-${Staging.run}"
        val failed = api.call("GET", "/v1/auth/me", headers = mapOf("X-Request-ID" to clientId))
        assertEquals(UNAUTHORIZED, failed.status)
        val ours = requireNotNull(failed.header("X-Request-ID")) { "every response carries X-Request-ID" }
        assertNotEquals(clientId, ours, "a client's ID is never taken as ours")
        assertEquals(UUID_V7, UUID.fromString(ours).version(), "the service's ID is a UUID v7")
        assertEquals(ours, failed.json.str("request_id"), "the problem body names the same request")

        val served = api.expect(OK, "GET", "/v1/auth/me", token = Staging.a.waiter, headers = mapOf("X-Request-ID" to "$clientId-ok"))
        val servedId = requireNotNull(served.header("X-Request-ID"))

        TRACE.parentFile.mkdirs()
        TRACE.writeText("$ours $clientId\n$servedId $clientId-ok\n")
        println("[ST-18] request IDs $ours and $servedId written to $TRACE for deploy/staging/log-review.py")
    }

    companion object {
        /** The IDs this run's ST-18 calls got, one `<request_id> <client_request_id>` per line. */
        val TRACE = File("build/system-test/st18-request-ids.txt")
        private const val UUID_V7 = 7
    }
}
