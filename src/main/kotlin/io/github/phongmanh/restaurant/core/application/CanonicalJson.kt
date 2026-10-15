package io.github.phongmanh.restaurant.core.application

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * RFC 8785 JSON Canonicalization Scheme, for the audit hash chain (core-and-auth §12): one exact byte
 * string per JSON value, whatever its key order or spacing, so a row hashes the same when it's read
 * back from `jsonb`.
 *
 * Numbers are limited to integers within ±2^53, which print the same in every JSON implementation;
 * that covers everything the audit log stores. A fraction or a larger number is rejected rather
 * than formatted, which is the part of RFC 8785 that is easy to get subtly wrong.
 */
object CanonicalJson {
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    fun encode(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

    /** Whether [element] holds only values this encoder accepts. */
    fun isSupported(element: JsonElement): Boolean = runCatching { encode(element) }.isSuccess

    private fun write(
        element: JsonElement,
        out: StringBuilder,
    ) {
        when (element) {
            is JsonObject -> {
                out.append('{')
                // RFC 8785 §3.2.3: keys sorted by UTF-16 code units, which is how Kotlin compares strings.
                element.entries.sortedBy { it.key }.forEachIndexed { i, (key, value) ->
                    if (i > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    write(value, out)
                }
                out.append('}')
            }

            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { i, value ->
                    if (i > 0) out.append(',')
                    write(value, out)
                }
                out.append(']')
            }

            JsonNull -> {
                out.append("null")
            }

            is JsonPrimitive -> {
                when {
                    element.isString -> writeString(element.content, out)
                    element.booleanOrNull != null -> out.append(element.booleanOrNull)
                    else -> out.append(integer(element))
                }
            }
        }
    }

    private fun integer(element: JsonPrimitive): Long {
        // `jsonb` may hand back 12 as "12" or, if it was stored that way, "12.0".
        val value =
            element.longOrNull
                ?: element.doubleOrNull?.takeIf { it % 1.0 == 0.0 && kotlin.math.abs(it) <= MAX_SAFE_INTEGER }?.toLong()
        require(value != null && value in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) { "only integers within ±2^53 are supported" }
        return value
    }

    /** RFC 8785 §3.2.2.2: the shortest escapes, lower-case hex, everything else as is. */
    private fun writeString(
        s: String,
        out: StringBuilder,
    ) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> {
                    out.append("\\\"")
                }

                '\\' -> {
                    out.append("\\\\")
                }

                '\b' -> {
                    out.append("\\b")
                }

                '\u000C' -> {
                    out.append("\\f")
                }

                '\n' -> {
                    out.append("\\n")
                }

                '\r' -> {
                    out.append("\\r")
                }

                '\t' -> {
                    out.append("\\t")
                }

                else -> {
                    if (c < ' ') {
                        out.append("\\u").append(c.code.toString(HEX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
                    } else {
                        out.append(c)
                    }
                }
            }
        }
        out.append('"')
    }

    private const val HEX = 16
    private const val UNICODE_ESCAPE_DIGITS = 4
}
