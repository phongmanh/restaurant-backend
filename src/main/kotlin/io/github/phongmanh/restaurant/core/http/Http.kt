package io.github.phongmanh.restaurant.core.http

import com.fasterxml.uuid.Generators
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingHandler
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

/**
 * JSON for every request and response (core-and-auth §2): unknown request fields are ignored, and
 * absent optional fields are left out rather than sent as `null`.
 */
val ApiJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

const val REQUEST_ID_HEADER = "X-Request-ID"

private val uuidV7 = Generators.timeBasedEpochGenerator()

/** A new UUID v7, the format of every ID the service makes. */
fun newUuidV7(): String = uuidV7.generate().toString()

/** The plugins every route shares. Installed before any route. */
fun Application.installHttp() {
    install(CallId) {
        // Always ours: a client-sent X-Request-ID is never trusted (core-and-auth §2).
        generate { newUuidV7() }
        replyToHeader(REQUEST_ID_HEADER)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("request_id")
        mdc("client_request_id") { it.request.header(REQUEST_ID_HEADER) }
        // The path only: query strings can carry user input, which stays out of logs (architecture §11).
        format { call -> "${call.response.status()?.value} ${call.request.httpMethod.value} ${call.request.path()}" }
    }
    install(ContentNegotiation) {
        json(ApiJson)
    }
    installProblemDetails()
}

/** Declares the route of a registry [endpoint]. Feature route files use this, never path literals. */
fun Route.endpoint(
    endpoint: Endpoint,
    body: RoutingHandler,
): Route = route(endpoint.path, endpoint.method) { handle(body) }
