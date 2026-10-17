package io.github.phongmanh.restaurant.staging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A small client for a staging deployment (deploy/README.md). Every request carries the
 * Deployment Protection bypass header, and the client stops at [budget] requests, as test-plan §9's
 * budget guard asks. A `429` is waited out and retried: the app's own carries `Retry-After`, and the
 * edge WAF's carries none, so it waits for the next 60 s window.
 *
 * Error messages hold the method, the path, the status and the problem's `code` and `request_id`,
 * never a body: a body can hold a link or a token.
 */
class StagingApi(
    baseUrl: String,
    private val bypassSecret: String,
    private val budget: Int,
    private val out: PrintStream,
) {
    private val base = baseUrl.trimEnd('/')
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_S)).build()

    /** Requests sent so far, retries included. */
    var sent = 0
        private set

    class Response(
        val status: Int,
        val body: JsonElement?,
    ) {
        val json: JsonObject get() = requireNotNull(body).jsonObject

        fun text(field: String): String = json.getValue(field).jsonPrimitive.content
    }

    fun call(
        method: String,
        path: String,
        body: JsonElement? = null,
        token: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        repeat(MAX_TRIES) {
            check(sent < budget) { "the run's request budget of $budget is spent (test-plan §9)" }
            sent++
            val request =
                HttpRequest
                    .newBuilder(URI(base + path))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
                    .header("x-vercel-protection-bypass", bypassSecret)
                    .header("X-Client", CLIENT)
                    .header("Accept", "application/json")
                    .apply {
                        token?.let { header("Authorization", "Bearer $it") }
                        headers.forEach { (name, value) -> header(name, value) }
                        if (body == null) {
                            method(method, HttpRequest.BodyPublishers.noBody())
                        } else {
                            header("Content-Type", "application/json")
                            method(method, HttpRequest.BodyPublishers.ofString(body.toString()))
                        }
                    }.build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != TOO_MANY_REQUESTS) {
                return Response(response.statusCode(), response.body().takeIf { it.isNotBlank() }?.let(Json::parseToJsonElement))
            }
            val wait =
                response
                    .headers()
                    .firstValue("Retry-After")
                    .map { it.toLong() }
                    .orElse(EDGE_WINDOW_S)
            out.println("429 on $method $path: waiting ${wait}s")
            Thread.sleep(wait * MILLIS)
        }
        error("$method $path was still refused with 429 after $MAX_TRIES tries")
    }

    /** [call], failing unless the status is [expected]. */
    fun expect(
        expected: Int,
        method: String,
        path: String,
        body: JsonElement? = null,
        token: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val response = call(method, path, body, token, headers)
        check(response.status == expected) {
            val problem = (response.body as? JsonObject).orEmpty()
            val code = problem["code"]?.jsonPrimitive?.content
            val requestId = problem["request_id"]?.jsonPrimitive?.content
            "$method $path answered ${response.status}, expected $expected (code=$code, request_id=$requestId)"
        }
        return response
    }

    private companion object {
        const val CLIENT = "staging-tools/1.0.0"
        const val MAX_TRIES = 5
        const val TOO_MANY_REQUESTS = 429
        const val EDGE_WINDOW_S = 61L
        const val MILLIS = 1_000L
        const val CONNECT_TIMEOUT_S = 10L

        /** A cold start took about 9 s on Hobby; this leaves room for a slow one. */
        const val REQUEST_TIMEOUT_S = 60L
    }
}

/** A required setting from the environment, as `./gradlew migrate` reads it (deploy/README.md step 5). */
fun env(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("$name isn't set: export deploy/staging/staging.env and deploy/staging/.env.staging first (deploy/README.md)")
