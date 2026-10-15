package spike

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpikeModuleTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val platform = mapOf("VERCEL_REGION" to "sin1", "SECRET_TOKEN" to "must-not-leak")

    private fun ApplicationTestBuilder.spike(stats: InstanceStats = InstanceStats()) {
        application { spikeModule(stats, cpuDispatcher = Dispatchers.Unconfined, env = platform::get) }
    }

    @Test
    fun `health live - answers ok`() = testApplication {
        spike()

        val response = client.get("/health/live")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun `info - two requests - only the first is the instance's first request`() = testApplication {
        spike()

        val first = json.decodeFromString<InstanceInfo>(client.get("/spike/info").bodyAsText())
        val second = json.decodeFromString<InstanceInfo>(client.get("/spike/info").bodyAsText())

        assertTrue(first.firstRequestOnInstance)
        assertFalse(second.firstRequestOnInstance)
        assertEquals(first.instanceId, second.instanceId)
        assertEquals(2, second.requestsServed)
    }

    @Test
    fun `info - platform and headers - echoes only the allow-listed ones`() = testApplication {
        spike()

        val body = client.get("/spike/info") {
            header("X-Forwarded-For", "203.0.113.7")
            header("Authorization", "Bearer secret")
        }.bodyAsText()
        val info = json.decodeFromString<InstanceInfo>(body)

        assertEquals(mapOf("VERCEL_REGION" to "sin1"), info.platform)
        assertEquals(mapOf("x-forwarded-for" to "203.0.113.7"), info.request.proxyHeaders)
        assertFalse("secret" in body)
    }

    @Test
    fun `sleep - valid ms - waits and reports this request in flight`() = testApplication {
        spike()

        val result = json.decodeFromString<SleepResult>(client.get("/spike/sleep?ms=50").bodyAsText())

        assertEquals(50, result.requestedMs)
        assertTrue(result.actualMs >= 50)
        assertEquals(1, result.inFlightAtStart)
    }

    @Test
    fun `sleep - missing, malformed or out-of-range ms - 400`() = testApplication {
        spike()

        listOf("/spike/sleep", "/spike/sleep?ms=abc", "/spike/sleep?ms=0", "/spike/sleep?ms=3600001").forEach {
            assertEquals(HttpStatusCode.BadRequest, client.get(it).status, it)
        }
    }

    @Test
    fun `cpu - out-of-range ms - 400`() = testApplication {
        spike()

        assertEquals(HttpStatusCode.BadRequest, client.get("/spike/cpu?ms=10001").status)
    }

    @Test
    fun `sse - one second at 200 ms - a stream of numbered events and an end event`() = testApplication {
        spike()

        val response = client.get("/spike/sse?seconds=1&intervalMs=200")
        val body = response.bodyAsText()

        assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
        assertEquals("no-cache", response.headers["Cache-Control"])
        assertTrue("id: 0\ndata: {\"n\":0," in body)
        assertTrue("event: end" in body)
        assertTrue(body.split("\n\n").count { it.startsWith("id: ") } in 4..6)
    }
}
