package spike

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The probe itself must answer exactly what it's asked, or the edge results mean nothing. */
class ProbeTest {
    @Test
    fun `patch - answers the asked status and ETag, and echoes If-Match`() = testApplication {
        application { probeModule() }

        val response = client.patch("/probe?id=a&etag=%222%22") { header(HttpHeaders.IfMatch, "\"1\"") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("\"2\"", response.headers[HttpHeaders.ETag])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("service", body["source"]?.jsonPrimitive?.content)
        assertEquals("\"1\"", body["if_match"]?.jsonPrimitive?.content)
    }

    @Test
    fun `patch - without etag sends none, a problem and no cache header when asked`() = testApplication {
        application { probeModule() }

        val response = client.patch("/probe?id=b&status=412&problem=true&cache=none")

        assertEquals(HttpStatusCode.PreconditionFailed, response.status)
        assertNull(response.headers[HttpHeaders.ETag])
        assertNull(response.headers[HttpHeaders.CacheControl])
        assertEquals(ContentType.Application.ProblemJson, response.contentType()?.withoutParameters())
    }

    @Test
    fun `hits - counts each call by id`() = testApplication {
        application { probeModule() }

        repeat(2) { client.patch("/probe?id=c&status=204") }

        val hits = Json.parseToJsonElement(client.get("/hits?id=c").bodyAsText()).jsonObject
        assertEquals(2, hits["hits"]?.jsonPrimitive?.int)
    }
}
