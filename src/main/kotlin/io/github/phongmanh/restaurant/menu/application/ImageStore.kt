package io.github.phongmanh.restaurant.menu.application

import java.io.IOException

/**
 * Where menu photos live (menu §6): public, immutable objects on the store's own domain, never the
 * API's, fetched by guests' phones without a token. The adapter is Vercel Blob (TBD-13, decided
 * 2026-11-07). Every call is a remote call, so it runs outside any database transaction
 * (architecture §9).
 */
interface ImageStore {
    /**
     * Stores [bytes] as a public, immutable object at [pathname] and returns its public URL. A
     * pathname is never reused. Throws [ImageStoreUnavailable] when the store fails or times out.
     */
    suspend fun put(
        pathname: String,
        bytes: ByteArray,
        contentType: String,
    ): String

    /** Deletes the objects at [urls]; one that's already gone is no error. Throws [ImageStoreUnavailable]. */
    suspend fun delete(urls: List<String>)
}

/** The store failed, refused or timed out. Its message names a status, never a token or a body. */
class ImageStoreUnavailable(
    message: String,
) : IOException(message)

/** No store set (`BLOB_READ_WRITE_TOKEN` empty): uploads answer `503 image_storage_unavailable`, menus work. */
object NoImageStore : ImageStore {
    override suspend fun put(
        pathname: String,
        bytes: ByteArray,
        contentType: String,
    ): String = throw ImageStoreUnavailable("no image store configured")

    override suspend fun delete(urls: List<String>): Unit = throw ImageStoreUnavailable("no image store configured")
}
