package io.github.phongmanh.restaurant.core.http

import io.github.phongmanh.restaurant.core.application.Caller
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
import io.ktor.server.routing.application
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.get
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
    install(ResponseHeaders)
}

/** HTTPS only, for two years (architecture §8 edge, §11 HTTP hardening); browsers ignore it over plain HTTP. */
const val HSTS_VALUE = "max-age=63072000; includeSubDomains"

/**
 * The service sends JSON only, so a browser that renders a response anyway may load nothing, run
 * nothing and be framed by no one (ASVS V3.4.3, V3.4.6; F-2, step 6a).
 */
const val CSP_VALUE = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"

/**
 * The headers every response carries (architecture §11, HTTP hardening): `Strict-Transport-Security`
 * and `X-Content-Type-Options: nosniff`, so a browser never reads a JSON body as another type;
 * [CSP_VALUE], and `Referrer-Policy: no-referrer`, so a link followed from a response sends no URL
 * (ASVS V3.4.5). And
 * `Cache-Control: no-store` on every API response, unless the route set its own (the menu reads
 * send `private, no-cache` with an `ETag`; §2, CR-004). Not Ktor's `DefaultHeaders`, whose
 * `Server` header would name the framework and its version.
 *
 * Each is added only when it's absent: a problem response passes through `onCallRespond` twice
 * (the failed call, then the problem body), and appending both times sent the headers twice (ZAP
 * rule 10035, ST-11).
 */
private val ResponseHeaders =
    createApplicationPlugin("ResponseHeaders") {
        onCallRespond { call, _ ->
            fun once(
                name: String,
                value: String,
            ) {
                if (call.response.headers[name] == null) call.response.header(name, value)
            }
            once(HttpHeaders.StrictTransportSecurity, HSTS_VALUE)
            once("X-Content-Type-Options", "nosniff")
            once("Content-Security-Policy", CSP_VALUE)
            once("Referrer-Policy", "no-referrer")
            if (call.request.path().startsWith("/v1/")) once(HttpHeaders.CacheControl, "no-store")
        }
    }

/**
 * Who the caller is, from the request's credentials (core-and-auth §6). The auth feature provides
 * it; [Route.endpoint] calls it for every route of a group that needs a token.
 */
fun interface CallAuthenticator {
    /** The caller, or a `DomainError` (401, 403 `restaurant_suspended`, or 404 for staff on admin routes). */
    suspend fun authenticate(
        call: ApplicationCall,
        endpoint: Endpoint,
    ): Caller
}

private val CallerKey = AttributeKey<Caller>("caller")

/**
 * Declares the route of a registry [endpoint]. Feature route files use this, never path literals.
 *
 * The path prefix decides the authentication (§2): a route of the staff, admin or guest group runs
 * only after [CallAuthenticator] has found its caller, so no route of those groups can forget it.
 * The handler reads the caller with [caller]. `/v1/auth` routes authenticate themselves where needed,
 * and so does the one [Endpoint.qrCredential] route, whose QR token is in the body (tables §8).
 */
fun Route.endpoint(
    endpoint: Endpoint,
    body: RoutingHandler,
): Route {
    val authenticator = if (endpoint.group == RouteGroup.AUTH || endpoint.qrCredential) null else application.get<CallAuthenticator>()
    return route(endpoint.path, endpoint.method) {
        handle {
            authenticator?.let { call.attributes.put(CallerKey, it.authenticate(call, endpoint)) }
            body()
        }
    }
}

/**
 * Declares one handler for a `PATCH` update and its `POST <path>/update` twin (CR-012), so the two
 * can't drift apart. Each route authenticates as [endpoint] does.
 */
fun Route.endpoints(
    patch: Endpoint,
    post: Endpoint,
    body: RoutingHandler,
) {
    require(post.method == HttpMethod.Post && post.path == "${patch.path}/update") { "$post isn't the twin of $patch" }
    endpoint(patch, body)
    endpoint(post, body)
}

/** The caller [Route.endpoint] authenticated. Only on routes of the staff, admin and guest groups. */
fun ApplicationCall.caller(): Caller = attributes[CallerKey]

/** The caller of a `/v1/staff` route: its group accepts only staff tokens. */
fun ApplicationCall.staffCaller(): Caller.Staff = caller() as Caller.Staff

/** The caller of a `/v1/admin` route: its group accepts only admin tokens. */
fun ApplicationCall.adminCaller(): Caller.Admin = caller() as Caller.Admin

/** Where the request came from, for the audit log (§12). Never for application logs. */
fun ApplicationCall.requestMeta(): RequestMeta =
    RequestMeta(
        requestId = callId,
        // Set by Vercel's edge, not by the client.
        ip = request.header("x-real-ip"),
        userAgent = request.header(HttpHeaders.UserAgent),
    )
