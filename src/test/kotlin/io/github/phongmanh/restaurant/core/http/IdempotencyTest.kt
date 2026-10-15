package io.github.phongmanh.restaurant.core.http

import io.github.phongmanh.restaurant.core.application.Actor
import io.github.phongmanh.restaurant.core.application.AuditEvent
import io.github.phongmanh.restaurant.core.application.AuditLog
import io.github.phongmanh.restaurant.core.application.Tenant
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.testApp
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koin.ktor.ext.get
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IT-CORE-01 and IT-CORE-02 (core-and-auth §4, §15) through the whole HTTP pipeline. The operation
 * is made up for the test; its "change" is an audit entry, so the test can count changes.
 */
class IdempotencyTest {
    private var restaurant = RestaurantId(UuidV7.next())

    /** Two staff members' access tokens: keys are scoped to the caller. */
    private lateinit var tokens: List<String>
    private val endpoint = Endpoint(HttpMethod.Post, "/v1/staff/test-orders/{tableId}", true, false, false)

    @Serializable
    data class Order(
        val note: String,
    )

    @Serializable
    data class Placed(
        val id: String,
        val note: String,
    )

    @BeforeTest
    fun setUp() {
        TestDatabase.truncate()
        restaurant = AuthFixtures.restaurant()
        tokens = listOf("one", "two").map { AuthFixtures.accessToken(AuthFixtures.staff(restaurant, "$it@pho.example", password = null)) }
    }

    private fun withOperation(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApp(
            routes = { app ->
                val routes = app.get<IdempotentRoutes>()
                val audit = app.get<AuditLog>()
                endpoint(endpoint) {
                    // Test-only controls ride in the query, which isn't part of the fingerprint.
                    val sleepMs = call.request.queryParameters["sleepMs"]?.toLong() ?: 0
                    val reject = call.request.queryParameters["reject"] == "true"
                    routes.respond(call, endpoint, call.caller().idempotencyScope, Tenant.Restaurant(restaurant)) {
                        val order = call.receive<Order>()
                        audit.record(
                            this,
                            AuditEvent(
                                restaurant,
                                Actor.SYSTEM,
                                "test.order_placed",
                                details =
                                    JsonObject(
                                        mapOf("note" to JsonPrimitive(order.note)),
                                    ),
                            ),
                        )
                        delay(sleepMs)
                        if (reject) throw DomainError.NotFound()
                        val id = UuidV7.next().toString()
                        JsonResponse.of(
                            HttpStatusCode.Created,
                            Placed.serializer(),
                            Placed(id, order.note),
                            location = "/v1/staff/orders/$id",
                        )
                    }
                }
            },
            block = block,
        )

    private suspend fun HttpClient.place(
        key: String?,
        note: String = "two phở",
        table: String = "t1",
        caller: Int = 0,
        query: String = "",
    ): HttpResponse =
        post("/v1/staff/test-orders/$table$query") {
            key?.let { header(IDEMPOTENCY_KEY_HEADER, it) }
            bearerAuth(tokens[caller])
            contentType(ContentType.Application.Json)
            setBody("""{"note": "$note"}""")
        }

    private fun changes(): Int =
        TestDatabase.superuser().use { conn ->
            conn.prepareStatement("SELECT count(*) FROM audit_events WHERE restaurant_id = ?").use {
                it.setObject(1, restaurant.value)
                it.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private suspend fun HttpResponse.code(): String = ApiJson.decodeFromString<Problem>(bodyAsText()).code

    @Test
    fun `the same key and body three times make one change and the same answer`() =
        withOperation {
            val responses = (1..3).map { client.place("key-1") }
            assertEquals(1, changes())
            responses.forEach { assertEquals(HttpStatusCode.Created, it.status) }
            assertEquals(1, responses.map { it.bodyAsText() }.toSet().size, "identical bodies")
            assertEquals(1, responses.map { it.headers[HttpHeaders.Location] }.toSet().size, "identical Location")
            assertNull(responses[0].headers[IDEMPOTENT_REPLAYED_HEADER])
            assertEquals("true", responses[1].headers[IDEMPOTENT_REPLAYED_HEADER])
            assertEquals("true", responses[2].headers[IDEMPOTENT_REPLAYED_HEADER])
            assertTrue(responses[2].headers[HttpHeaders.ContentType]!!.startsWith("application/json"))
        }

    @Test
    fun `the same key with another body or path is 422`() =
        withOperation {
            client.place("key-1")
            val otherBody = client.place("key-1", note = "three phở")
            assertEquals(HttpStatusCode.UnprocessableEntity, otherBody.status)
            assertEquals("idempotency_key_reused", otherBody.code())
            assertEquals("idempotency_key_reused", client.place("key-1", table = "t2").code())
            assertEquals(1, changes())
        }

    @Test
    fun `keys are scoped to the caller`() =
        withOperation {
            client.place("key-1", caller = 0)
            val other = client.place("key-1", caller = 1)
            assertEquals(HttpStatusCode.Created, other.status)
            assertNull(other.headers[IDEMPOTENT_REPLAYED_HEADER])
            assertEquals(2, changes())
        }

    @Test
    fun `a missing or malformed key is 400`() =
        withOperation {
            assertEquals("idempotency_key_required", client.place(null).code())
            assertEquals("validation_failed", client.place("has space").code())
            assertEquals("validation_failed", client.place("x".repeat(65)).code())
            assertEquals(0, changes())
        }

    @Test
    fun `two copies at once make one change`() =
        withOperation {
            val responses =
                coroutineScope {
                    (1..2).map { async { client.place("key-1", query = "?sleepMs=500") } }.awaitAll()
                }
            assertEquals(listOf(HttpStatusCode.Created, HttpStatusCode.Created), responses.map { it.status })
            assertEquals(1, responses.map { it.bodyAsText() }.toSet().size)
            assertEquals(1, responses.count { it.headers[IDEMPOTENT_REPLAYED_HEADER] == "true" })
            assertEquals(1, changes())
        }

    @Test
    fun `a copy still waiting after 2 s is 409, and may retry`() =
        withOperation {
            val (slow, waiting) =
                coroutineScope {
                    val slow = async { client.place("key-1", query = "?sleepMs=3500") }
                    delay(300)
                    val waiting = async { client.place("key-1") }
                    slow.await() to waiting.await()
                }
            assertEquals(HttpStatusCode.Created, slow.status)
            assertEquals(HttpStatusCode.Conflict, waiting.status)
            assertEquals("idempotency_request_in_progress", waiting.code())
            val retry = client.place("key-1")
            assertEquals("true", retry.headers[IDEMPOTENT_REPLAYED_HEADER])
            assertEquals(1, changes())
        }

    @Test
    fun `a rejected request isn't stored, so its retry is judged again`() =
        withOperation {
            val rejected = client.place("key-1", query = "?reject=true")
            assertEquals(HttpStatusCode.NotFound, rejected.status)
            assertEquals(0, changes(), "the rejection rolled back the change and the key")
            val retry = client.place("key-1")
            assertEquals(HttpStatusCode.Created, retry.status)
            assertNull(retry.headers[IDEMPOTENT_REPLAYED_HEADER])
            assertEquals(1, changes())
        }
}
