package io.github.phongmanh.restaurant.support

import io.github.phongmanh.restaurant.menu.application.ImageStore
import io.github.phongmanh.restaurant.menu.application.ImageStoreUnavailable
import java.util.concurrent.ConcurrentHashMap

/**
 * The image store for tests (menu §6): objects in memory under [DOMAIN], up or [down], or refusing
 * puts after [failAfterPuts] more. Tests never reach Vercel Blob; `VercelBlobLiveTest` checks
 * the real one when `BLOB_READ_WRITE_TOKEN` is set.
 */
class FakeImageStore : ImageStore {
    val objects = ConcurrentHashMap<String, Pair<String, ByteArray>>()

    @Volatile var down = false

    /** Puts that succeed before every further put fails (deletes still work); `null` = no limit. */
    @Volatile var failAfterPuts: Int? = null

    override suspend fun put(
        pathname: String,
        bytes: ByteArray,
        contentType: String,
    ): String {
        failAfterPuts?.let { if (it <= 0) throw ImageStoreUnavailable("fake store refused the put") else failAfterPuts = it - 1 }
        if (down) throw ImageStoreUnavailable("fake store down")
        val url = "$DOMAIN/$pathname"
        check(objects.putIfAbsent(url, contentType to bytes) == null) { "a pathname is never reused: $pathname" }
        return url
    }

    override suspend fun delete(urls: List<String>) {
        if (down) throw ImageStoreUnavailable("fake store down")
        urls.forEach { objects.remove(it) }
    }

    companion object {
        const val DOMAIN = "https://store-id.public.blob.vercel-storage.com"
    }
}
