package spike

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One route, `/probe`, for every method. The query decides the answer, so the script can ask for
 * the exact response the service sends and see what Vercel's edge does with it:
 *
 * - `status`: the status code (default 200); 204 and 304 send no body.
 * - `etag`: the `ETag` header, sent as given (quotes included); absent sends none.
 * - `cache`: `Cache-Control` (default `no-store`, as the service's `/v1` responses); `none` sends none.
 * - `problem=true`: `application/problem+json`, as the service's errors.
 * - `id`: names the call, so `GET /hits?id=` tells whether the handler ran even when the edge
 *   replaced its answer (the change "is saved" half of D-1).
 *
 * The body echoes the conditional headers the handler received, so the script also sees whether
 * the edge forwards or strips them.
 */
fun Application.probeModule(hits: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()) {
    val instance = UUID.randomUUID().toString().take(8)
    routing {
        get("/health/live") { call.respondText("ok") }
        get("/hits") {
            val id = call.request.queryParameters["id"].orEmpty()
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(
                buildJsonObject {
                    put("id", id)
                    put("hits", hits[id]?.get() ?: 0)
                    put("instance", instance)
                }.toString(),
                ContentType.Application.Json,
            )
        }
        route("/probe") {
            handle {
                val query = call.request.queryParameters
                val id = query["id"].orEmpty()
                val count = hits.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
                val status = HttpStatusCode.fromValue(query["status"]?.toIntOrNull() ?: HttpStatusCode.OK.value)
                query["etag"]?.let { call.response.header(HttpHeaders.ETag, it) }
                val cache = query["cache"] ?: "no-store"
                if (cache != "none") call.response.header(HttpHeaders.CacheControl, cache)
                call.response.header("X-Probe-Id", id)

                if (status == HttpStatusCode.NoContent || status == HttpStatusCode.NotModified) {
                    call.respond(status)
                    return@handle
                }
                val problem = query["problem"].toBoolean()
                val body = buildJsonObject {
                    put("source", "service")
                    put("id", id)
                    put("method", call.request.httpMethod.value)
                    put("if_match", JsonPrimitive(call.request.header(HttpHeaders.IfMatch)))
                    put("if_none_match", JsonPrimitive(call.request.header(HttpHeaders.IfNoneMatch)))
                    put("hits", count)
                    put("instance", instance)
                    if (problem) put("code", "precondition_failed")
                }
                val type = if (problem) ContentType.Application.ProblemJson else ContentType.Application.Json
                call.respondText(body.toString(), type, status)
            }
        }
    }
}
