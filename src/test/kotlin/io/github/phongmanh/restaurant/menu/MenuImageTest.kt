package io.github.phongmanh.restaurant.menu

import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.menu.application.ImageStore
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.FakeImageStore
import io.github.phongmanh.restaurant.support.MutableClock
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.TestKeys
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.authApp
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.obj
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.github.phongmanh.restaurant.tables.job
import io.ktor.client.HttpClient
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonObject
import org.koin.dsl.module
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** IT-MENU-14 (menu §6, FR-022): item photos, checked by content, re-encoded, kept on the store's domain. */
class MenuImageTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val e = Endpoints.Menu

    private fun imageApp(
        store: FakeImageStore = FakeImageStore(),
        clock: MutableClock = MutableClock(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = authApp(clock = clock, overrides = listOf(module { single<ImageStore> { store } }), block = block)

    private suspend fun HttpClient.upload(
        token: String?,
        itemId: String,
        bytes: ByteArray,
        type: ContentType = ContentType.Image.JPEG,
    ): HttpResponse =
        api(e.PUT_MENU_ITEM_IMAGE, token, "itemId" to itemId) {
            contentType(type)
            setBody(bytes)
        }

    private suspend fun HttpClient.removeImage(
        token: String?,
        itemId: String,
    ): HttpResponse = api(e.DELETE_MENU_ITEM_IMAGE, token, "itemId" to itemId)

    @Test
    fun `IT-MENU-14 a photo is checked by its bytes, re-encoded as two JPEGs and served from the store`() {
        val store = FakeImageStore()
        imageApp(store) {
            val a = Tenants.create("A", "manager@a.example")
            val item = client.item(a.managerToken, client.category(a.managerToken, "Phở"), "Phở bò")
            val id = item.str("id")
            val before = menuVersion(a.id)

            // A PNG sent as image/jpeg is accepted: the bytes decide, not the header.
            val put = client.upload(a.managerToken, id, png(1600, 1200, alpha = true))
            assertEquals(HttpStatusCode.OK, put.status)
            val body = put.json()
            val image = body.obj("image")
            val url = image.str("url")
            val thumb = image.str("thumb_url")
            assertTrue(url.startsWith("${FakeImageStore.DOMAIN}/menu/${a.id}/$id/") && url.endsWith("-800.jpg"), url)
            assertEquals(url.removeSuffix("-800.jpg") + "-320.jpg", thumb)
            assertEquals(item.str("version"), body.str("version"), "a photo doesn't make a manager's open edit stale")
            assertEquals(before + 1, menuVersion(a.id))
            assertEquals(listOf("menu.category.created", "menu.item.created", "menu.item.image_changed"), menuAudit(a.id))

            assertEquals(setOf(url, thumb), store.objects.keys)
            assertEquals(800 to 600, size(store.objects.getValue(url)))
            assertEquals(320 to 240, size(store.objects.getValue(thumb)))

            // Every menu read names it; the guest's phone loads it from the store, never the API.
            assertEquals(
                url,
                client
                    .guestMenu(a.id)
                    .json()
                    .firstItem()
                    .obj("image")
                    .str("url"),
            )
            assertEquals(
                thumb,
                client
                    .api(e.GET_MENU_ITEM, a.managerToken, "itemId" to id)
                    .json()
                    .obj("image")
                    .str("thumb_url"),
            )
        }
    }

    @Test
    fun `IT-MENU-14 the output is a fresh JPEG without the upload's metadata, and small photos aren't enlarged`() {
        val store = FakeImageStore()
        imageApp(store) {
            val a = Tenants.create("A", "manager@a.example")
            val id = client.item(a.managerToken, client.category(a.managerToken, "Phở"), "Phở bò").str("id")

            val withExif = jpegWithExif(120, 90, "GPS 21.0285 105.8542 secret")
            assertTrue(String(withExif, Charsets.ISO_8859_1).contains("Exif"), "the fixture carries EXIF")
            assertEquals(HttpStatusCode.OK, client.upload(a.managerToken, id, withExif).status)
            store.objects.values.forEach { (type, bytes) ->
                assertEquals("image/jpeg", type)
                assertEquals(0xFF.toByte() to 0xD8.toByte(), bytes[0] to bytes[1])
                val text = String(bytes, Charsets.ISO_8859_1)
                assertFalse("Exif" in text || "secret" in text, "no EXIF segment survives")
                assertEquals(120 to 90, size(type to bytes))
            }

            val webp = Base64.getDecoder().decode(WEBP_1X1)
            assertEquals(HttpStatusCode.OK, client.upload(a.managerToken, id, webp, ContentType("image", "webp")).status)
        }
    }

    @Test
    fun `IT-MENU-14 a PDF is 415, over 4 MB is 413, a 20,000 x 20,000 header is 400 without decoding`() {
        val store = FakeImageStore()
        imageApp(store) {
            val a = Tenants.create("A", "manager@a.example")
            val id = client.item(a.managerToken, client.category(a.managerToken, "Phở"), "Phở bò").str("id")

            val pdf = "%PDF-1.7\n%âãÏÓ\n1 0 obj\n<<>>\nendobj\n".toByteArray()
            assertEquals("unsupported_media_type", client.upload(a.managerToken, id, pdf, ContentType.Application.Pdf).problem())
            assertEquals("unsupported_media_type", client.upload(a.managerToken, id, pdf).problem(), "named a JPEG, still a PDF")

            val tooBig = ByteArray(4 * 1024 * 1024 + 1).also { png(10, 10).copyInto(it) }
            val big = client.upload(a.managerToken, id, tooBig, ContentType.Image.PNG)
            assertEquals(HttpStatusCode.PayloadTooLarge, big.status)
            assertEquals("payload_too_large", big.problem())

            val started = System.nanoTime()
            val bomb = client.upload(a.managerToken, id, pngHeader(20_000, 20_000), ContentType.Image.PNG)
            assertEquals(HttpStatusCode.BadRequest, bomb.status)
            assertEquals("image_invalid", bomb.problem())
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "refused from the header, before decoding")

            val broken = png(50, 50).copyOf(60)
            assertEquals("image_invalid", client.upload(a.managerToken, id, broken, ContentType.Image.PNG).problem())

            assertEquals(emptyMap(), store.objects.toMap(), "nothing refused reaches the store")
            assertNull(client.api(e.GET_MENU_ITEM, a.managerToken, "itemId" to id).json()["image"])
        }
    }

    @Test
    fun `IT-MENU-14 managers only, and another restaurant's item is 404 and unchanged`() {
        val store = FakeImageStore()
        imageApp(store) {
            val a = Tenants.create("A", "manager@a.example")
            val b = Tenants.create("B", "manager@b.example")
            val (_, waiter) = a.member("waiter@a.example", "waiter")
            val (_, kitchen) = a.member("kitchen@a.example", "kitchen")
            val bItem = client.item(b.managerToken, client.category(b.managerToken, "Cơm"), "Cơm tấm").str("id")
            val aItem = client.item(a.managerToken, client.category(a.managerToken, "Phở"), "Phở bò").str("id")
            val photo = png(64, 64)

            assertEquals(HttpStatusCode.Unauthorized, client.upload(null, aItem, photo).status)
            assertEquals(HttpStatusCode.Unauthorized, client.removeImage(null, aItem).status)
            assertEquals("forbidden", client.upload(waiter, aItem, photo).problem())
            assertEquals("forbidden", client.upload(kitchen, aItem, photo).problem())
            assertEquals("forbidden", client.removeImage(kitchen, aItem).problem())

            assertEquals(HttpStatusCode.OK, client.upload(b.managerToken, bItem, photo).status)
            val bImage = client.api(e.GET_MENU_ITEM, b.managerToken, "itemId" to bItem).json().obj("image")
            val stored = store.objects.keys.toSet()

            assertEquals(HttpStatusCode.NotFound, client.upload(a.managerToken, bItem, photo).status)
            assertEquals(HttpStatusCode.NotFound, client.removeImage(a.managerToken, bItem).status)
            val unknown = "019a0000-0000-7000-8000-000000000000"
            assertEquals(HttpStatusCode.NotFound, client.upload(a.managerToken, unknown, photo).status)
            assertEquals(HttpStatusCode.NotFound, client.removeImage(a.managerToken, unknown).status)

            assertEquals(stored, store.objects.keys, "a 404 leaves nothing in the store")
            assertEquals(bImage, client.api(e.GET_MENU_ITEM, b.managerToken, "itemId" to bItem).json().obj("image"))
            assertEquals(emptyList(), menuAudit(a.id).filter { it == "menu.item.image_changed" })
        }
    }

    @Test
    fun `IT-MENU-14 a store that's down is 503 and leaves no half-stored photo, and the menu still reads`() {
        val store = FakeImageStore()
        imageApp(store) {
            val a = Tenants.create("A", "manager@a.example")
            val id = client.item(a.managerToken, client.category(a.managerToken, "Phở"), "Phở bò").str("id")
            val version = menuVersion(a.id)

            store.down = true
            val down = client.upload(a.managerToken, id, png(64, 64))
            assertEquals(HttpStatusCode.ServiceUnavailable, down.status)
            assertEquals("image_storage_unavailable", down.problem())
            assertTrue(down.headers["Retry-After"] != null)

            store.down = false
            store.failAfterPuts = 1
            assertEquals("image_storage_unavailable", client.upload(a.managerToken, id, png(64, 64)).problem())
            store.failAfterPuts = null
            assertEquals(emptyMap(), store.objects.toMap(), "the large copy stored before the failure is deleted again")
            assertEquals(version, menuVersion(a.id))
            assertEquals(HttpStatusCode.OK, client.guestMenu(a.id).status)
            assertNull(client.guestMenu(a.id).json().firstItem()["image"])
        }
    }

    @Test
    fun `IT-MENU-14 a replaced, removed or deleted item's photo stays 30 days, then the retention job deletes it`() {
        val store = FakeImageStore()
        val clock = MutableClock()
        imageApp(store, clock) {
            val a = Tenants.create("A", "manager@a.example")
            val category = client.category(a.managerToken, "Phở")
            val id = client.item(a.managerToken, category, "Phở bò").str("id")
            val other = client.item(a.managerToken, category, "Phở gà")

            val first =
                client
                    .upload(a.managerToken, id, png(64, 64))
                    .json()
                    .obj("image")
                    .str("url")
            val second =
                client
                    .upload(a.managerToken, id, png(64, 64))
                    .json()
                    .obj("image")
                    .str("url")
            assertNotEquals(first, second, "every upload gets a new name, so a cached old URL never shows the new photo")
            assertEquals(4, store.objects.size, "the replaced photo stays for now")

            // Removing: 204, the item has no image, and a second remove changes nothing.
            val version = menuVersion(a.id)
            assertEquals(HttpStatusCode.NoContent, client.removeImage(a.managerToken, id).status)
            assertNull(client.api(e.GET_MENU_ITEM, a.managerToken, "itemId" to id).json()["image"])
            assertEquals(version + 1, menuVersion(a.id))
            assertEquals(HttpStatusCode.NoContent, client.removeImage(a.managerToken, id).status)
            assertEquals(version + 1, menuVersion(a.id), "nothing to remove: no new menu version")

            // Deleting an item with a photo retires it too.
            val otherId = other.str("id")
            client.upload(a.managerToken, otherId, png(64, 64))
            val deleted =
                client.api(e.DELETE_MENU_ITEM, a.managerToken, "itemId" to otherId) {
                    headers.append("If-Match", "\"${other.str("version")}\"")
                }
            assertEquals(HttpStatusCode.NoContent, deleted.status)
            assertEquals(3, retirements(a))
            assertEquals(6, store.objects.size)

            clock.advance(29.days)
            assertEquals("0", runRetention().str("menu_images"), "not yet 30 days")
            assertEquals(6, store.objects.size)

            clock.advance(2.days)
            assertEquals("3", runRetention().str("menu_images"))
            assertEquals(emptyMap(), store.objects.toMap())
            assertEquals(0, retirements(a))
            assertEquals("0", runRetention().str("menu_images"), "a second run finds nothing")
        }
    }

    @Test
    fun `IT-MENU-14 a store that's down at retention keeps the rows for the next run`() {
        val store = FakeImageStore()
        val clock = MutableClock()
        imageApp(store, clock) {
            val a = Tenants.create("A", "manager@a.example")
            val id = client.item(a.managerToken, client.category(a.managerToken, "Phở"), "Phở bò").str("id")
            client.upload(a.managerToken, id, png(64, 64))
            client.removeImage(a.managerToken, id)
            clock.advance(31.days)

            store.down = true
            assertEquals("0", runRetention().str("menu_images"))
            assertEquals(1, retirements(a))
            store.down = false
            assertEquals("1", runRetention().str("menu_images"))
            assertEquals(0, retirements(a))
        }
    }

    private suspend fun ApplicationTestBuilder.runRetention(): JsonObject {
        val run = client.job(Endpoints.Jobs.RETENTION, TestKeys.CRON_SECRET)
        assertEquals(HttpStatusCode.OK, run.status)
        return run.json().obj("tasks")
    }

    private fun retirements(tenant: io.github.phongmanh.restaurant.support.TestTenant): Int =
        AuthFixtures.query("SELECT count(*) FROM menu_image_retirements WHERE restaurant_id = ?", tenant.id.value) { it.getInt(1) }.single()

    private fun JsonObject.firstItem(): JsonObject =
        array("categories")
            .objects()
            .first()
            .array("items")
            .objects()
            .first()

    private companion object {
        /** A 1 × 1 lossless WebP. */
        const val WEBP_1X1 = "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA=="

        fun size(obj: Pair<String, ByteArray>): Pair<Int, Int> = ImageIO.read(obj.second.inputStream()).let { it.width to it.height }

        fun png(
            width: Int,
            height: Int,
            alpha: Boolean = false,
        ): ByteArray {
            val image = BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = Color(200, 80, 40, if (alpha) 128 else 255)
            g.fillRect(0, 0, width, height / 2)
            g.dispose()
            return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }

        /** A JPEG with an APP1 `Exif` segment holding [secret], right after the start-of-image marker. */
        fun jpegWithExif(
            width: Int,
            height: Int,
            secret: String,
        ): ByteArray {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
            val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + secret.toByteArray()
            val segment =
                byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + ByteBuffer.allocate(2).putShort((payload.size + 2).toShort()).array() + payload
            return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
        }

        /** A PNG signature and a valid IHDR chunk for [width] × [height], with no pixel data after it. */
        fun pngHeader(
            width: Int,
            height: Int,
        ): ByteArray {
            val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val data =
                ByteBuffer
                    .allocate(
                        17,
                    ).put("IHDR".toByteArray())
                    .putInt(width)
                    .putInt(height)
                    .put(byteArrayOf(8, 2, 0, 0, 0))
                    .array()
            val crc = CRC32().apply { update(data) }.value.toInt()
            return signature + ByteBuffer.allocate(4).putInt(13).array() + data + ByteBuffer.allocate(4).putInt(crc).array()
        }
    }
}
