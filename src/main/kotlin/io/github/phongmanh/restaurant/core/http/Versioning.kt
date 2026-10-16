package io.github.phongmanh.restaurant.core.http

import io.github.phongmanh.restaurant.core.domain.DomainError
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/*
 * Optimistic locking over HTTP (core-and-auth §5). A read of a versioned resource sends
 * `ETag: "<version>"`; a change must send it back in `If-Match`. The service then updates with
 * `… WHERE version = ?` and calls `requireVersionedUpdate` (core.application).
 */

/** The strong ETag of [version]: the number in double quotes. */
fun etagOf(version: Int): String = "\"$version\""

/**
 * The version the client last read, from `If-Match`. Missing → `428 precondition_required`. A value
 * that names no version (`*`, a weak or malformed tag) can never match → `412 precondition_failed`.
 */
fun ApplicationCall.requireIfMatch(): Int {
    val value = request.header(HttpHeaders.IfMatch) ?: throw DomainError.PreconditionRequired()
    return ETAG
        .matchEntire(value.trim())
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull() ?: throw DomainError.PreconditionFailed()
}

/**
 * Whether the client already holds the copy with [etag] (`If-None-Match`, RFC 9110 §13.1.2): the
 * header lists it, weak or strong, or is `*`. No header → `false`.
 */
fun ApplicationCall.holdsCurrent(etag: String): Boolean {
    val value = request.header(HttpHeaders.IfNoneMatch) ?: return false
    return value.split(',').map { it.trim().removePrefix("W/") }.any { it == "*" || it == etag }
}

private val ETAG = Regex("\"([0-9]{1,9})\"")
