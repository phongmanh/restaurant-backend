package io.github.phongmanh.restaurant.core.http

import io.github.phongmanh.restaurant.core.application.Idempotency
import io.github.phongmanh.restaurant.core.application.IdempotencyRequest
import io.github.phongmanh.restaurant.core.application.IdempotentOutcome
import io.github.phongmanh.restaurant.core.application.StoredResponse
import io.github.phongmanh.restaurant.core.application.Tenant
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.application.inTransaction
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.RoutingCall
import kotlinx.serialization.KSerializer

/**
 * A successful response, built inside the transaction so it can be stored with the key. Only the
 * headers worth replaying are kept: `Location` and `ETag` (core-and-auth §4).
 */
class JsonResponse(
    val status: HttpStatusCode,
    /** The JSON body; empty for `204`. */
    val body: ByteArray,
    val location: String? = null,
    val etag: String? = null,
) {
    companion object {
        fun <T> of(
            status: HttpStatusCode,
            serializer: KSerializer<T>,
            value: T,
            location: String? = null,
            etag: String? = null,
        ) = JsonResponse(status, ApiJson.encodeToString(serializer, value).toByteArray(), location, etag)

        fun noContent() = JsonResponse(HttpStatusCode.NoContent, ByteArray(0))
    }
}

/**
 * The `Idempotent { }` route helper (core-and-auth §4, §14) for the operations whose registry
 * entry requires an `Idempotency-Key`.
 *
 * It opens the operation's transaction ([tenant]) and runs [handle] in it, so the key, the change
 * and the stored response commit or roll back together. A service called from [handle] that opens
 * its own transaction for the same tenant joins this one.
 *
 * [scope] is the caller's `Caller.idempotencyScope`.
 */
class IdempotentRoutes(
    private val transactor: Transactor,
    private val idempotency: Idempotency,
) {
    suspend fun respond(
        call: RoutingCall,
        endpoint: Endpoint,
        scope: String,
        tenant: Tenant,
        handle: suspend TxScope.() -> JsonResponse,
    ) {
        require(endpoint.idempotencyKey) { "$endpoint takes no Idempotency-Key" }
        val request =
            IdempotencyRequest.of(
                scope = scope,
                operation = endpoint.toString(),
                key = call.request.header(IDEMPOTENCY_KEY_HEADER),
                pathParameters = call.pathParameters.entries().associate { (name, values) -> name to values.joinToString(",") },
                // Read before the transaction opens, so no connection is held while the body arrives.
                body = call.receive<ByteArray>(),
            )
        val outcome =
            transactor.inTransaction(tenant) {
                idempotency.execute(this, request) { handle().toStored() }
            }
        if (outcome is IdempotentOutcome.Replayed) call.response.header(IDEMPOTENT_REPLAYED_HEADER, "true")
        call.respondStored(outcome.response)
    }

    private fun JsonResponse.toStored() =
        StoredResponse(
            status = status.value,
            headers = listOfNotNull(location?.let { HttpHeaders.Location to it }, etag?.let { HttpHeaders.ETag to it }).toMap(),
            body = body,
        )
}

private suspend fun ApplicationCall.respondStored(stored: StoredResponse) {
    stored.headers.forEach { (name, value) -> response.header(name, value) }
    val status = HttpStatusCode.fromValue(stored.status)
    if (stored.body.isEmpty()) {
        respond(status)
    } else {
        respondBytes(stored.body, ContentType.Application.Json, status)
    }
}
