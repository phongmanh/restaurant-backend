package io.github.phongmanh.restaurant.core.http

import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.FieldError
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.util.Base64

/*
 * Cursor pagination (core-and-auth §2): `?limit=` (default 50, at most 200) and `?cursor=`. The
 * response is `{"items": [...], "next_cursor": "..."}`, without `next_cursor` on the last page.
 */

/** One page of a list. */
@Serializable
data class Page<T>(
    val items: List<T>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/**
 * What the client asked for. [after] is the sort key of the last item it has, or `null` for the
 * first page. Repositories fetch `limit + 1` rows to know whether there's a next page.
 */
data class PageRequest(
    val limit: Int,
    val after: List<String>?,
)

/**
 * The opaque cursor: base64url of the last item's sort key, as a JSON array of strings. A
 * feature picks the key's parts (e.g. created time and ID) and their order.
 */
object Cursor {
    private const val MAX_LENGTH = 512
    private val serializer: KSerializer<List<String>> = ListSerializer(String.serializer())

    fun encode(sortKey: List<String>): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ApiJson.encodeToString(serializer, sortKey).toByteArray())

    /** The sort key, or `null` if [cursor] isn't one this service made. */
    fun decode(cursor: String): List<String>? =
        cursor
            .takeIf { it.length <= MAX_LENGTH }
            ?.let { runCatching { ApiJson.decodeFromString(serializer, String(Base64.getUrlDecoder().decode(it))) }.getOrNull() }
}

const val DEFAULT_PAGE_SIZE = 50
const val MAX_PAGE_SIZE = 200

/** Reads `limit` and `cursor`. Either one invalid → `400 validation_failed`. */
fun ApplicationCall.pageRequest(): PageRequest {
    val errors = mutableListOf<FieldError>()
    val limitParam = request.queryParameters["limit"]
    val limit = if (limitParam == null) DEFAULT_PAGE_SIZE else limitParam.toIntOrNull()?.takeIf { it in 1..MAX_PAGE_SIZE }
    if (limit == null) errors += FieldError("query.limit", "out_of_range", "an integer from 1 to $MAX_PAGE_SIZE")
    val cursorParam = request.queryParameters["cursor"]
    val after = cursorParam?.let(Cursor::decode)
    if (cursorParam != null && after == null) errors += FieldError("query.cursor", "invalid", "use next_cursor from the previous page")
    if (errors.isNotEmpty()) throw DomainError.ValidationFailed(errors)
    return PageRequest(limit!!, after)
}

/**
 * The page for [rows], fetched with `limit + 1`: the extra row only signals a next page, whose cursor
 * is the sort key of the last row returned.
 */
fun <T> pageOf(
    rows: List<T>,
    request: PageRequest,
    sortKey: (T) -> List<String>,
): Page<T> {
    val items = rows.take(request.limit)
    val next = if (rows.size > request.limit) Cursor.encode(sortKey(items.last())) else null
    return Page(items, next)
}
