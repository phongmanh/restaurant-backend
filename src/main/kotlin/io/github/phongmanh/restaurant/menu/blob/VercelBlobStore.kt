package io.github.phongmanh.restaurant.menu.blob

import io.github.phongmanh.restaurant.menu.application.ImageStore
import io.github.phongmanh.restaurant.menu.application.ImageStoreUnavailable
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Vercel Blob (TBD-13) over its REST API, the calls Vercel's own JS SDK (`@vercel/blob` 2.8.2) makes,
 * since there is no JVM SDK (menu §6):
 * - `PUT <api>/?pathname=…` with the bytes, `x-vercel-blob-access: public`, no random suffix, no
 *   overwrite, and a one-year cache; it answers the object's public URL on the store's domain;
 * - `POST <api>/delete` with `{"urls": […]}`.
 *
 * Both carry the store's read-write token (`BLOB_READ_WRITE_TOKEN`) and the store ID it names. Each
 * call has 5 s (architecture §10); a timeout, a network error or any answer but `200` is
 * [ImageStoreUnavailable], which names the status only. It doesn't retry: the manager does.
 */
class VercelBlobStore(
    private val token: String,
    private val apiUrl: String = DEFAULT_API_URL,
    private val timeout: Duration = TIMEOUT,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(timeout.toJavaDuration()).build(),
) : ImageStore {
    /** The store ID inside the token, `vercel_blob_rw_<store id>_<secret>`. */
    private val storeId: String =
        token.split('_').getOrNull(STORE_ID_PART)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("BLOB_READ_WRITE_TOKEN isn't a Vercel Blob read-write token")

    override suspend fun put(
        pathname: String,
        bytes: ByteArray,
        contentType: String,
    ): String {
        val request =
            request("/?pathname=" + URLEncoder.encode(pathname, Charsets.UTF_8))
                .header("x-vercel-blob-access", "public")
                .header("x-content-type", contentType)
                .header("x-add-random-suffix", "0")
                .header("x-allow-overwrite", "0")
                .header("x-cache-control-max-age", CACHE_MAX_AGE_SECONDS.toString())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build()
        val body = send(request, "put")
        val url =
            runCatching {
                Json
                    .parseToJsonElement(body)
                    .jsonObject["url"]
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull()
                ?: throw ImageStoreUnavailable("blob put answered without a url")
        if (!url.startsWith("https://") || !url.endsWith("/$pathname")) throw ImageStoreUnavailable("blob put answered an unexpected url")
        return url
    }

    override suspend fun delete(urls: List<String>) {
        if (urls.isEmpty()) return
        val body = JsonObject(mapOf("urls" to JsonArray(urls.map(::JsonPrimitive)))).toString()
        val request =
            request("/delete")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        send(request, "delete")
    }

    private fun request(path: String): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI(apiUrl + path))
            .timeout(timeout.toJavaDuration())
            .header("Authorization", "Bearer $token")
            .header("x-api-version", API_VERSION)
            .header("x-vercel-blob-store-id", storeId)

    private suspend fun send(
        request: HttpRequest,
        what: String,
    ): String {
        val response = exchange(request, what)
        if (response.statusCode() != HTTP_OK) throw ImageStoreUnavailable("blob $what answered ${response.statusCode()}")
        return response.body()
    }

    private suspend fun exchange(
        request: HttpRequest,
        what: String,
    ): HttpResponse<String> =
        try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (_: HttpTimeoutException) {
            throw ImageStoreUnavailable("blob $what timed out")
        } catch (_: IOException) {
            throw ImageStoreUnavailable("blob $what failed to connect")
        }

    override fun toString(): String = "VercelBlobStore(***)"

    companion object {
        const val DEFAULT_API_URL = "https://vercel.com/api/blob"

        /** The REST API version `@vercel/blob` 2.8.2 sends. */
        const val API_VERSION = "13"
        const val CACHE_MAX_AGE_SECONDS = 31_536_000L
        private const val STORE_ID_PART = 3
        private const val HTTP_OK = 200
        val TIMEOUT = 5.seconds
    }
}
