package io.github.phongmanh.restaurant.core.http

import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.FieldError
import io.github.phongmanh.restaurant.service
import io.github.phongmanh.restaurant.support.TestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The error model (core-and-auth §3) and the request ID (§2). The parts of IT-CORE-04 that need no
 * database. The routes under `/test/` exist only in this test.
 */
class ProblemDetailsTest {
    @Serializable
    data class Body(
        val name: String,
    )

    private fun withRoutes(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                service(TestDatabase.config)
                routing {
                    get("/test/boom") { error("secret internal detail") }
                    get("/test/not-found") { throw DomainError.NotFound() }
                    get("/test/invalid") {
                        throw DomainError.ValidationFailed(listOf(FieldError("/name", "required", "must be present")))
                    }
                    post("/test/echo") { call.respond(call.receive<Body>()) }
                }
            }
            block()
        }

    private suspend fun HttpResponse.problem(): Problem {
        assertEquals(ProblemJson, contentType()?.withoutParameters())
        return ApiJson.decodeFromString(bodyAsText())
    }

    @Test
    fun `an unexpected exception is a 500 without internals, with the request id`() =
        withRoutes {
            val response = client.get("/test/boom")
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val text = response.bodyAsText()
            assertFalse("secret internal detail" in text)
            assertFalse("IllegalStateException" in text)
            val problem = response.problem()
            assertEquals("internal_error", problem.code)
            assertEquals("/problems/internal_error", problem.type)
            assertEquals(response.headers[REQUEST_ID_HEADER], problem.requestId)
        }

    @Test
    fun `malformed JSON is a 400 malformed_request`() =
        withRoutes {
            val response =
                client.post("/test/echo") {
                    contentType(ContentType.Application.Json)
                    setBody("{\"name\": ")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("malformed_request", response.problem().code)
        }

    @Test
    fun `unknown request fields are ignored`() =
        withRoutes {
            val response =
                client.post("/test/echo") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name": "a", "added_later": 1}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `a body that isn't JSON is a 415`() =
        withRoutes {
            val response =
                client.post("/test/echo") {
                    contentType(ContentType.Text.Plain)
                    setBody("name")
                }
            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
            assertEquals("unsupported_media_type", response.problem().code)
        }

    @Test
    fun `a domain error maps to its code and status`() =
        withRoutes {
            val response = client.get("/test/not-found")
            assertEquals(HttpStatusCode.NotFound, response.status)
            val problem = response.problem()
            assertEquals("not_found", problem.code)
            assertNull(problem.errors, "errors only appear on validation problems")
        }

    @Test
    fun `validation_failed lists its field errors`() =
        withRoutes {
            val problem = client.get("/test/invalid").problem()
            assertEquals("validation_failed", problem.code)
            assertEquals(listOf(ProblemFieldError("/name", "required", "must be present")), problem.errors)
        }

    @Test
    fun `an unknown path or method is a 404 not_found`() =
        withRoutes {
            assertEquals("not_found", client.get("/v1/nothing-here").problem().code)
            val wrongMethod = client.post(Endpoints.HEALTH)
            assertEquals(HttpStatusCode.NotFound, wrongMethod.status)
            assertEquals("not_found", wrongMethod.problem().code)
        }

    @Test
    fun `every response has our own request id, never the client's`() =
        withRoutes {
            val first = client.get(Endpoints.HEALTH) { header(REQUEST_ID_HEADER, "client-chosen") }
            val second = client.get(Endpoints.HEALTH)
            val id = first.headers[REQUEST_ID_HEADER]!!
            assertNotEquals("client-chosen", id)
            assertNotEquals(id, second.headers[REQUEST_ID_HEADER])
            // UUID v7: version nibble 7.
            assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id), id)
        }

    @Test
    fun `health answers 200`() =
        withRoutes {
            val response = client.get(Endpoints.HEALTH)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"status":"ok"}""", response.bodyAsText())
            assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith("application/json"))
        }
}
