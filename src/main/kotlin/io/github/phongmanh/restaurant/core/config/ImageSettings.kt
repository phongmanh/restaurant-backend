package io.github.phongmanh.restaurant.core.config

/**
 * Menu photos (menu §6, FR-022; TBD-13: Vercel Blob). [blobToken] (`BLOB_READ_WRITE_TOKEN`) is the
 * store's read-write token; it never leaves the server or reaches a log. Without it, uploads answer
 * `503 image_storage_unavailable` and the menus work, without new photos.
 */
class ImageSettings(
    val blobToken: String?,
) {
    override fun toString(): String = "ImageSettings(blobToken=${if (blobToken == null) "none" else "***"})"

    companion object {
        val NONE = ImageSettings(null)
    }
}
