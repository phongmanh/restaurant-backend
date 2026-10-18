package io.github.phongmanh.restaurant.core.http

import io.github.phongmanh.restaurant.core.application.requireVersionedUpdate
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.support.testApp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The shared HTTP rules of core-and-auth §2 and §5: IT-CORE-03, IT-CORE-08 and the headers. */
class HttpConventionsTest {
    private val version = AtomicInteger(1)

    private fun withRoutes(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApp(
            routes = {
                get("/v1/test/ok") { call.respond(mapOf("ok" to "yes")) }
                get("/v1/test/menu") {
                    call.response.header(HttpHeaders.CacheControl, "private, no-cache")
                    call.respond(mapOf("ok" to "yes"))
                }
                get("/v1/test/down") { throw DomainError.ServiceUnavailable() }
                post("/v1/test/echo") { call.respondText(call.receiveText().length.toString()) }
                // A versioned resource kept in memory: the optimistic-locking pattern of §5.
                get("/v1/test/item") {
                    call.response.header(HttpHeaders.ETag, etagOf(version.get()))
                    call.respond(mapOf("ok" to "yes"))
                }
                patch("/v1/test/item") {
                    val expected = call.requireIfMatch()
                    val updated = if (version.compareAndSet(expected, expected + 1)) 1 else 0
                    requireVersionedUpdate(updated) { true }
                    call.response.header(HttpHeaders.ETag, etagOf(version.get()))
                    call.respond(mapOf("ok" to "yes"))
                }
                get("/v1/test/page") {
                    val request = call.pageRequest()
                    val all = (1..5).map { it.toString() }
                    val rows = all.filter { request.after == null || it > request.after.single() }.take(request.limit + 1)
                    call.respondText(
                        ApiJson.encodeToString(Page.serializer(String.serializer()), pageOf(rows, request) { listOf(it) }),
                        ContentType.Application.Json,
                    )
                }
            },
            block = block,
        )

    private suspend fun HttpResponse.code(): String = ApiJson.decodeFromString<Problem>(bodyAsText()).code

    @Test
    fun `X-Client below the app's minimum is 426, otherwise served (IT-CORE-08)`() =
        withRoutes {
            val old = client.get("/v1/test/ok") { header(X_CLIENT_HEADER, "customer-android/0.9.0") }
            assertEquals(HttpStatusCode.UpgradeRequired, old.status)
            assertEquals("client_upgrade_required", old.code())
            for (value in listOf(null, "customer-android/1.0.0", "customer-android/1.10.0", "customer-web/0.1.0")) {
                val response = client.get("/v1/test/ok") { value?.let { header(X_CLIENT_HEADER, it) } }
                assertEquals(HttpStatusCode.OK, response.status, value)
            }
            val malformed = client.get("/v1/test/ok") { header(X_CLIENT_HEADER, "customer-android/1.0") }
            assertEquals("validation_failed", malformed.code())
        }

    @Test
    fun `If-Match missing is 428, stale or unusable is 412, current is 200 with the new ETag (IT-CORE-03)`() =
        withRoutes {
            val etag = client.get("/v1/test/item").headers[HttpHeaders.ETag]!!
            assertEquals("\"1\"", etag)
            val missing = client.patch("/v1/test/item")
            assertEquals(HttpStatusCode.PreconditionRequired, missing.status)
            assertEquals("precondition_required", missing.code())
            val current = client.patch("/v1/test/item") { header(HttpHeaders.IfMatch, etag) }
            assertEquals(HttpStatusCode.OK, current.status)
            assertEquals("\"2\"", current.headers[HttpHeaders.ETag])
            for (stale in listOf(etag, "*", "W/\"2\"", "2")) {
                val response = client.patch("/v1/test/item") { header(HttpHeaders.IfMatch, stale) }
                assertEquals(HttpStatusCode.PreconditionFailed, response.status, stale)
                assertEquals("precondition_failed", response.code())
            }
        }

    @Test
    fun `API responses are no-store unless the route says otherwise`() =
        withRoutes {
            assertEquals("no-store", client.get("/v1/test/ok").headers[HttpHeaders.CacheControl])
            assertEquals("no-store", client.get("/v1/test/nothing").headers[HttpHeaders.CacheControl], "problems too")
            assertEquals("private, no-cache", client.get("/v1/test/menu").headers[HttpHeaders.CacheControl])
            assertNull(client.get(Endpoints.HEALTH).headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `every response carries the security headers once, and no Server header`() =
        withRoutes {
            // An unknown path too: ZAP found its 404 with the headers twice (ST-11, step 6a).
            for (path in listOf("/v1/test/ok", "/v1/test/nothing", "/v1/test/down", "/v1/test/no-such-route", Endpoints.HEALTH)) {
                val headers = client.get(path).headers
                assertEquals(listOf(HSTS_VALUE), headers.getAll(HttpHeaders.StrictTransportSecurity), path)
                assertEquals(listOf("nosniff"), headers.getAll("X-Content-Type-Options"), path)
                assertEquals(listOf(CSP_VALUE), headers.getAll("Content-Security-Policy"), path)
                assertEquals(listOf("no-referrer"), headers.getAll("Referrer-Policy"), path)
                assertNull(headers[HttpHeaders.Server], path)
            }
        }

    @Test
    fun `a body over 64 KB is 413`() =
        withRoutes {
            fun body(size: Int) = "\"" + "x".repeat(size - 2) + "\""
            val fits =
                client.post("/v1/test/echo") {
                    contentType(ContentType.Application.Json)
                    setBody(body(MAX_BODY_BYTES.toInt()))
                }
            assertEquals(HttpStatusCode.OK, fits.status)
            val tooLarge =
                client.post("/v1/test/echo") {
                    contentType(ContentType.Application.Json)
                    setBody(body(MAX_BODY_BYTES.toInt() + 1))
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
            assertEquals("payload_too_large", tooLarge.code())
        }

    @Test
    fun `503 carries Retry-After`() =
        withRoutes {
            val response = client.get("/v1/test/down")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("service_unavailable", response.code())
            assertEquals(DomainError.DEFAULT_RETRY_AFTER_SECONDS.toString(), response.headers[HttpHeaders.RetryAfter])
        }

    @Test
    fun `CORS allows the configured origin with the API's headers, and no credentials`() =
        withRoutes {
            val preflight =
                client.options("/v1/test/ok") {
                    header(HttpHeaders.Origin, "https://order.example.test")
                    header(HttpHeaders.AccessControlRequestMethod, "POST")
                    header(HttpHeaders.AccessControlRequestHeaders, "authorization,idempotency-key,if-match,x-client,content-type")
                }
            assertEquals(HttpStatusCode.OK, preflight.status)
            assertEquals("https://order.example.test", preflight.headers[HttpHeaders.AccessControlAllowOrigin])
            assertNull(preflight.headers[HttpHeaders.AccessControlAllowCredentials])

            val actual = client.get("/v1/test/ok") { header(HttpHeaders.Origin, "https://order.example.test") }
            val exposed = actual.headers[HttpHeaders.AccessControlExposeHeaders].orEmpty().lowercase()
            for (h in listOf("etag", "location", "retry-after", "x-request-id", "idempotent-replayed")) {
                assert(h in exposed) { "$h not exposed: $exposed" }
            }

            val other = client.get("/v1/test/ok") { header(HttpHeaders.Origin, "https://evil.example") }
            assertNull(other.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `lists page with a cursor, and a bad limit or cursor is 400`() =
        withRoutes {
            val first = ApiJson.decodeFromString(Page.serializer(String.serializer()), client.get("/v1/test/page?limit=2").bodyAsText())
            assertEquals(listOf("1", "2"), first.items)
            val second =
                ApiJson.decodeFromString(
                    Page.serializer(String.serializer()),
                    client.get("/v1/test/page?limit=2&cursor=${first.nextCursor}").bodyAsText(),
                )
            assertEquals(listOf("3", "4"), second.items)
            val last = client.get("/v1/test/page?limit=2&cursor=${second.nextCursor}").bodyAsText()
            assertEquals("""{"items":["5"]}""", last, "no next_cursor on the last page")

            for (query in listOf("limit=0", "limit=201", "limit=ten", "cursor=not-ours", "cursor=" + "A".repeat(600))) {
                val response = client.get("/v1/test/page?$query")
                assertEquals(HttpStatusCode.BadRequest, response.status, query)
                assertEquals("validation_failed", response.code(), query)
            }
        }
}
