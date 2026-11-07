package io.github.phongmanh.restaurant.menu.application

import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.IdGenerator
import io.github.phongmanh.restaurant.menu.domain.ItemId
import io.github.phongmanh.restaurant.menu.domain.MenuItem
import io.github.phongmanh.restaurant.menu.image.ImageProcessor
import io.github.phongmanh.restaurant.menu.persistence.ItemRepository
import io.github.phongmanh.restaurant.menu.persistence.MenuImageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * A manager's item photo (menu §6, FR-022). The upload is checked and re-encoded first (`415`,
 * `400`), then the item is looked up (`404`), then both copies go to the [ImageStore] (`503` when it's
 * down), and only then does one menu write set the item's `image_key`. So the remote calls run
 * outside the transaction (architecture §9), and objects whose write fails are deleted again.
 *
 * Last writer wins, without `If-Match` or `Idempotency-Key`: a retry uploads again, and the newest
 * photo is the item's. It doesn't change the item's version, so a manager's open edit stays current.
 * The photo it replaces is kept for [MenuImages.RETIREMENT] (guests may still show a menu that names
 * it), then [ImageRetention] deletes it.
 */
class ImageService(
    private val transactor: Transactor,
    private val writer: MenuWriter,
    private val items: ItemRepository,
    private val images: MenuImageRepository,
    private val store: ImageStore,
    private val processor: ImageProcessor,
    private val ids: IdGenerator,
) {
    /** Decoding takes 0.2–0.5 s and up to ~160 MB at 40 megapixels: two at a time per instance, like Argon2id. */
    private val processing = Semaphore(PARALLEL_UPLOADS)

    suspend fun upload(
        editor: MenuEditor,
        id: ItemId,
        bytes: ByteArray,
        request: RequestMeta,
    ): MenuItem {
        val image = processing.withPermit { withContext(Dispatchers.Default) { processor.process(bytes) } }
        // Before the upload, so a wrong ID leaves nothing in the store; the write checks again.
        transactor.inTransaction(editor.restaurantId) { items.find(this, editor.restaurantId, id) } ?: throw DomainError.NotFound()

        val pathname = "menu/${editor.restaurantId}/$id/${ids.next()}"
        val key = storeBoth(pathname, image.large, image.thumb)
        var saved = false
        try {
            val item =
                writer.write(editor.restaurantId, editor.actor, request) {
                    val current = items.find(tx, restaurantId, id) ?: throw DomainError.NotFound()
                    images.setImage(tx, restaurantId, id, key, now)
                    current.imageKey?.let { images.retire(tx, restaurantId, it, now.plus(MenuImages.RETIREMENT)) }
                    changed(IMAGE_CHANGED, MenuEditService.ITEM_TARGET, id.value, mapOf("image" to JsonPrimitive("set")))
                    items.find(tx, restaurantId, id)!!
                }
            saved = true
            return item
        } finally {
            if (!saved) withContext(NonCancellable) { deleteQuietly(key) }
        }
    }

    /** Removes the photo; an item without one is a `204` all the same. */
    suspend fun remove(
        editor: MenuEditor,
        id: ItemId,
        request: RequestMeta,
    ) {
        writer.write(editor.restaurantId, editor.actor, request) {
            val current = items.find(tx, restaurantId, id) ?: throw DomainError.NotFound()
            val key = current.imageKey ?: return@write
            images.setImage(tx, restaurantId, id, null, now)
            images.retire(tx, restaurantId, key, now.plus(MenuImages.RETIREMENT))
            changed(IMAGE_CHANGED, MenuEditService.ITEM_TARGET, id.value, mapOf("image" to JsonPrimitive("removed")))
        }
    }

    /** Both copies, or neither: a half-stored photo is deleted before the `503`. */
    private suspend fun storeBoth(
        pathname: String,
        large: ByteArray,
        thumb: ByteArray,
    ): String {
        var key: String? = null
        try {
            key = store.put(MenuImages.largePath(pathname), large, JPEG).removeSuffix(MenuImages.LARGE_SUFFIX)
            val thumbUrl = store.put(MenuImages.thumbPath(pathname), thumb, JPEG)
            if (thumbUrl != MenuImages.thumbUrl(key)) throw ImageStoreUnavailable("blob put answered another store's url")
            return key
        } catch (e: ImageStoreUnavailable) {
            log.warn("menu image upload failed: {}", e.message)
            key?.let { withContext(NonCancellable) { deleteQuietly(it) } }
            throw DomainError.ImageStorageUnavailable()
        }
    }

    /** Best effort: what's left is only an unused public object, never named by a menu. */
    private suspend fun deleteQuietly(key: String) {
        try {
            store.delete(MenuImages.urls(key))
        } catch (e: ImageStoreUnavailable) {
            log.warn("unused menu image left in the store: {}", e.message)
        }
    }

    companion object {
        const val IMAGE_CHANGED = "menu.item.image_changed"
        private const val PARALLEL_UPLOADS = 2
        private const val JPEG = "image/jpeg"
        private val log = LoggerFactory.getLogger(ImageService::class.java)
    }
}

/**
 * How an item's `image_key` names its two objects (menu §6): the key is the large copy's public URL
 * without its `-800.jpg` ending, so a menu read needs no store settings to build the URLs.
 */
object MenuImages {
    const val LARGE_SUFFIX = "-800.jpg"
    const val THUMB_SUFFIX = "-320.jpg"

    private const val RETIREMENT_DAYS = 30L

    /** How long a replaced or removed photo stays in the store. */
    val RETIREMENT: Duration = Duration.ofDays(RETIREMENT_DAYS)

    fun largePath(pathname: String) = pathname + LARGE_SUFFIX

    fun thumbPath(pathname: String) = pathname + THUMB_SUFFIX

    fun largeUrl(key: String) = key + LARGE_SUFFIX

    fun thumbUrl(key: String) = key + THUMB_SUFFIX

    fun urls(key: String) = listOf(largeUrl(key), thumbUrl(key))
}
