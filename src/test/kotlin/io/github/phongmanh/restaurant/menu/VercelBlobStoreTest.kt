package io.github.phongmanh.restaurant.menu

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.phongmanh.restaurant.menu.application.ImageStoreUnavailable
import io.github.phongmanh.restaurant.menu.blob.VercelBlobStore
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Vercel Blob adapter (menu §6, TBD-13) against a local stand-in for Blob's REST API: the
 * requests `@vercel/blob` 2.8.2 makes, and every failure → [ImageStoreUnavailable] naming no secret.
 */
class VercelBlobStoreTest {
    private class Seen(
        val method: String,
        val uri: URI,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private val seen = mutableListOf<Seen>()
    private var answer: (HttpExchange) -> Pair<Int, String> = { 200 to "{}" }
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/blob") { ex ->
                val body = ex.requestBody.readAllBytes()
                synchronized(seen) {
                    seen += Seen(ex.requestMethod, ex.requestURI, ex.requestHeaders.mapValues { it.value.joinToString(",") }, body)
                }
                val (status, text) = answer(ex)
                val bytes = text.toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
    private val api = "http://127.0.0.1:${server.address.port}/api/blob"

    @AfterTest
    fun stop() = server.stop(0)

    private fun store(timeoutMs: Long = 2_000) = VercelBlobStore(TOKEN, api, timeoutMs.milliseconds)

    @Test
    fun `a put sends the bytes public, unsuffixed, not overwriting, cached a year, and answers the store's url`() =
        runBlocking {
            val path = "menu/r/i/${UUID.randomUUID()}-800.jpg"
            answer = { 200 to """{"url":"https://abcstore.public.blob.vercel-storage.com/$path","pathname":"$path"}""" }
            val url = store().put(path, byteArrayOf(1, 2, 3), "image/jpeg")
            assertEquals("https://abcstore.public.blob.vercel-storage.com/$path", url)

            val put = seen.single()
            assertEquals("PUT", put.method)
            assertEquals("/api/blob/", put.uri.path)
            assertEquals("pathname=$path", put.uri.query)
            assertEquals("Bearer $TOKEN", put.headers.header("Authorization"))
            assertEquals("13", put.headers.header("x-api-version"))
            assertEquals("AbcStore", put.headers.header("x-vercel-blob-store-id"))
            assertEquals("public", put.headers.header("x-vercel-blob-access"))
            assertEquals("image/jpeg", put.headers.header("x-content-type"))
            assertEquals("0", put.headers.header("x-add-random-suffix"))
            assertEquals("0", put.headers.header("x-allow-overwrite"))
            assertEquals("31536000", put.headers.header("x-cache-control-max-age"))
            assertEquals(listOf<Byte>(1, 2, 3), put.body.toList())
        }

    @Test
    fun `a delete posts the urls as JSON, and an empty list sends nothing`() =
        runBlocking {
            store().delete(
                listOf("https://s.public.blob.vercel-storage.com/a-800.jpg", "https://s.public.blob.vercel-storage.com/a-320.jpg"),
            )
            store().delete(emptyList())
            val delete = seen.single()
            assertEquals("POST", delete.method)
            assertEquals("/api/blob/delete", delete.uri.path)
            assertEquals("application/json", delete.headers.header("Content-Type"))
            assertEquals(
                """{"urls":["https://s.public.blob.vercel-storage.com/a-800.jpg","https://s.public.blob.vercel-storage.com/a-320.jpg"]}""",
                String(delete.body),
            )
        }

    @Test
    fun `an error status, a url for another path, a timeout or no server is unavailable, and says no secret`() =
        runBlocking {
            answer = { 403 to """{"error":{"code":"forbidden","message":"Access denied"}}""" }
            failsQuietly { store().put("menu/x-800.jpg", byteArrayOf(1), "image/jpeg") }
            failsQuietly { store().delete(listOf("https://s.public.blob.vercel-storage.com/x")) }

            answer = { 200 to """{"url":"https://evil.example/menu/x-800.jpg"}""" }
            failsQuietly { store().put("menu/y-800.jpg", byteArrayOf(1), "image/jpeg") }
            answer = { 200 to "not json" }
            failsQuietly { store().put("menu/y-800.jpg", byteArrayOf(1), "image/jpeg") }

            answer = {
                Thread.sleep(1_000)
                200 to "{}"
            }
            failsQuietly { store(timeoutMs = 200).delete(listOf("https://s.public.blob.vercel-storage.com/x")) }

            server.stop(0)
            failsQuietly { store().put("menu/z-800.jpg", byteArrayOf(1), "image/jpeg") }
        }

    @Test
    fun `a token that isn't a Blob read-write token is refused at startup, and the store never prints it`() {
        assertFailsWith<IllegalArgumentException> { VercelBlobStore("not-a-token", api) }
        assertFalse(TOKEN in store().toString())
    }

    private suspend fun failsQuietly(call: suspend () -> Unit) {
        try {
            call()
            fail("expected ImageStoreUnavailable")
        } catch (e: ImageStoreUnavailable) {
            assertFalse(e.message.orEmpty().contains("secret"), e.message)
        }
    }

    private fun Map<String, String>.header(name: String): String? = entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private companion object {
        const val TOKEN = "vercel_blob_rw_AbcStore_secretpart123"
    }
}

/**
 * The real Vercel Blob store (menu §6), when `BLOB_READ_WRITE_TOKEN` is set in the shell; skipped
 * otherwise, like `AblyLiveTest`. It stores one object, reads it back from its public URL without a
 * token, and deletes it.
 */
class VercelBlobLiveTest {
    @Test
    fun `the real store takes a public JPEG and deletes it again`() =
        runBlocking {
            val token = System.getenv("BLOB_READ_WRITE_TOKEN")?.takeIf { it.isNotBlank() } ?: return@runBlocking
            val store = VercelBlobStore(token)
            val bytes = MenuImageFixtures.tinyJpeg()
            val path = "menu/live-test/${UUID.randomUUID()}-800.jpg"
            val url = store.put(path, bytes, "image/jpeg")
            try {
                assertTrue(url.startsWith("https://") && url.endsWith(path), url)
                val get = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofByteArray())
                assertEquals(200, get.statusCode())
                assertEquals("image/jpeg", get.headers().firstValue("content-type").orElse(""))
                assertTrue(bytes.contentEquals(get.body()))
                assertTrue("max-age=31536000" in get.headers().firstValue("cache-control").orElse(""), get.headers().toString())
            } finally {
                store.delete(listOf(url))
            }
        }
}

object MenuImageFixtures {
    fun tinyJpeg(): ByteArray {
        val image = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
        return java.io
            .ByteArrayOutputStream()
            .also { javax.imageio.ImageIO.write(image, "jpeg", it) }
            .toByteArray()
    }
}
