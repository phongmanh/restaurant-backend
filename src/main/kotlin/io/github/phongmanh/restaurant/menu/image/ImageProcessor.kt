package io.github.phongmanh.restaurant.menu.image

import io.github.phongmanh.restaurant.core.domain.DomainError
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.stream.ImageInputStream

/** The two JPEG copies of an upload (menu §6): 800 px and 320 px on the long edge. */
class ProcessedImage(
    val large: ByteArray,
    val thumb: ByteArray,
)

/**
 * Checks and re-encodes a menu photo (menu §6), in the design's order:
 * 1. the magic bytes are a JPEG, PNG or WebP, whatever the request's `Content-Type` says → else
 *    `415 unsupported_media_type`;
 * 2. width × height, read from the header before anything is decoded, is at most 40 megapixels, so a
 *    "decompression bomb" can't exhaust memory → else `400 image_invalid`;
 * 3. it decodes → else `400 image_invalid`.
 *
 * The output is two fresh JPEGs at quality 80, which keeps none of the upload's metadata (EXIF
 * location included) or anything hidden in the file. Smaller pictures aren't enlarged. The size
 * limit (4 MB) is the route's body limit, checked before this runs.
 */
class ImageProcessor {
    fun process(bytes: ByteArray): ProcessedImage {
        val format = formatOf(bytes) ?: throw DomainError.UnsupportedMediaType()
        val image = decode(bytes, format)
        return ProcessedImage(jpeg(scaled(image, LARGE_EDGE)), jpeg(scaled(image, THUMB_EDGE)))
    }

    private fun decode(
        bytes: ByteArray,
        format: String,
    ): BufferedImage {
        val input: ImageInputStream? = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        val reader: ImageReader? = ImageIO.getImageReadersByFormatName(format).asSequence().firstOrNull()
        if (input == null || reader == null) throw DomainError.ImageInvalid()
        return input.use {
            try {
                reader.setInput(input, true, true)
                val pixels = readOrInvalid { reader.getWidth(0).toLong() * reader.getHeight(0) }
                if (pixels !in 1..MAX_PIXELS) throw DomainError.ImageInvalid()
                readOrInvalid { reader.read(0) }
            } finally {
                reader.dispose()
            }
        }
    }

    /**
     * A broken file throws all sorts from inside ImageIO's readers; every one of them is
     * `image_invalid`, and what the reader said stays out of the answer and the logs.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private inline fun <T> readOrInvalid(read: () -> T): T =
        try {
            read()
        } catch (_: IOException) {
            throw DomainError.ImageInvalid()
        } catch (e: DomainError) {
            throw e
        } catch (_: RuntimeException) {
            throw DomainError.ImageInvalid()
        }

    /** RGB on white, so a transparent PNG doesn't turn black, at most [edge] px on the long edge. */
    private fun scaled(
        source: BufferedImage,
        edge: Int,
    ): BufferedImage {
        val long = maxOf(source.width, source.height)
        val ratio = if (long > edge) edge.toDouble() / long else 1.0
        var current = source
        var width = source.width
        var height = source.height
        val targetWidth = maxOf(1, Math.round(source.width * ratio).toInt())
        val targetHeight = maxOf(1, Math.round(source.height * ratio).toInt())
        // Halving step by step before the last step keeps a big shrink from aliasing.
        do {
            width = maxOf(targetWidth, width / 2)
            height = maxOf(targetHeight, height / 2)
            current = draw(current, width, height)
        } while (width != targetWidth || height != targetHeight)
        return current
    }

    private fun draw(
        source: BufferedImage,
        width: Int,
        height: Int,
    ): BufferedImage {
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = target.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)
            g.drawImage(source, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }
        return target
    }

    private fun jpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val param =
                    writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = JPEG_QUALITY
                    }
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    companion object {
        const val LARGE_EDGE = 800
        const val THUMB_EDGE = 320
        const val MAX_PIXELS = 40_000_000L
        const val JPEG_QUALITY = 0.8f

        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private const val WEBP_TAG_OFFSET = 8
        private const val MIN_HEADER = 12

        /** The ImageIO format the bytes start like, or `null`. */
        fun formatOf(bytes: ByteArray): String? {
            fun at(
                offset: Int,
                text: String,
            ) = text.indices.all { bytes[offset + it] == text[it].code.toByte() }
            return when {
                bytes.size < MIN_HEADER -> null
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpeg"
                PNG.indices.all { bytes[it] == PNG[it] } -> "png"
                at(0, "RIFF") && at(WEBP_TAG_OFFSET, "WEBP") -> "webp"
                else -> null
            }
        }
    }
}
