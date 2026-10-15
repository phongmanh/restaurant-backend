package io.github.phongmanh.restaurant.core.http

import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
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
const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
const val IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed"
const val X_CLIENT_HEADER = "X-Client"

/** Request bodies are JSON of at most 64 KB (§2). The menu image upload will raise it for its route. */
const val MAX_BODY_BYTES = 64L * 1024

/** A new UUID v7, the format of every ID the service makes. */
fun newUuidV7(): String = UuidV7.next().toString()

/** The plugins every route shares. Installed before any route. */
fun Application.installHttp(settings: HttpSettings) {
    // First, so failures in the plugins below become problem+json too.
    installProblemDetails()
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
    install(CORS) {
        // Only the customer web app calls cross-origin, with a bearer token and no cookies; the staff
        // and admin web apps go through a same-origin rewrite (§2, §8.3).
        settings.allowedOrigins.forEach { allowHost(it.substringAfter("://"), schemes = listOf(it.substringBefore("://"))) }
        listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete).forEach(::allowMethod)
        listOf(HttpHeaders.Authorization, HttpHeaders.ContentType, IDEMPOTENCY_KEY_HEADER, HttpHeaders.IfMatch, X_CLIENT_HEADER)
            .forEach(::allowHeader)
        listOf(HttpHeaders.ETag, HttpHeaders.Location, HttpHeaders.RetryAfter, REQUEST_ID_HEADER, IDEMPOTENT_REPLAYED_HEADER)
            .forEach(::exposeHeader)
        allowCredentials = false
    }
    install(RequestBodyLimit) { bodyLimit { MAX_BODY_BYTES } }
    // The idempotency helper reads the raw body for its fingerprint, then the route reads it again.
    install(DoubleReceive)
    install(ContentNegotiation) {
        json(ApiJson)
    }
    install(clientVersionPlugin(settings.minClientVersions))
    install(NoStore)
}

/**
 * `Cache-Control: no-store` on every API response, unless the route set its own (the menu reads
 * send `private, no-cache` with an `ETag`; §2, CR-004).
 */
private val NoStore =
    createApplicationPlugin("NoStore") {
        onCallRespond { call, _ ->
            if (call.request.path().startsWith("/v1/") && call.response.headers[HttpHeaders.CacheControl] == null) {
                call.response.header(HttpHeaders.CacheControl, "no-store")
            }
        }
    }

/** Declares the route of a registry [endpoint]. Feature route files use this, never path literals. */
fun Route.endpoint(
    endpoint: Endpoint,
    body: RoutingHandler,
): Route = route(endpoint.path, endpoint.method) { handle(body) }

/** Where the request came from, for the audit log (§12). Never for application logs. */
fun ApplicationCall.requestMeta(): RequestMeta =
    RequestMeta(
        requestId = callId,
        // Set by Vercel's edge, not by the client.
        ip = request.header("x-real-ip"),
        userAgent = request.header(HttpHeaders.UserAgent),
    )
