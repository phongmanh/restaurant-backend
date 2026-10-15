package io.github.phongmanh.restaurant.core.domain

/**
 * The stable error codes clients switch on (core-and-auth §3). Every code here is also in the
 * contract's `x-error-catalogue` with the same status; a test checks it. Feature codes join this
 * list when their feature is built.
 */
enum class ErrorCode(
    val value: String,
    val status: Int,
    val title: String,
) {
    MALFORMED_REQUEST("malformed_request", 400, "Malformed request"),
    VALIDATION_FAILED("validation_failed", 400, "Request validation failed"),
    IDEMPOTENCY_KEY_REQUIRED("idempotency_key_required", 400, "Idempotency-Key required"),
    PASSWORD_REJECTED("password_rejected", 400, "Password rejected"),
    UNAUTHENTICATED("unauthenticated", 401, "Unauthenticated"),
    INVALID_CREDENTIALS("invalid_credentials", 401, "Invalid credentials"),
    FORBIDDEN("forbidden", 403, "Forbidden"),
    RESTAURANT_SUSPENDED("restaurant_suspended", 403, "Restaurant suspended"),
    NOT_FOUND("not_found", 404, "Not found"),
    LINK_INVALID("link_invalid", 404, "Link invalid"),
    IDEMPOTENCY_REQUEST_IN_PROGRESS("idempotency_request_in_progress", 409, "Request in progress"),
    PRECONDITION_FAILED("precondition_failed", 412, "Precondition failed"),
    PAYLOAD_TOO_LARGE("payload_too_large", 413, "Payload too large"),
    UNSUPPORTED_MEDIA_TYPE("unsupported_media_type", 415, "Unsupported media type"),
    IDEMPOTENCY_KEY_REUSED("idempotency_key_reused", 422, "Idempotency-Key reused"),
    CLIENT_UPGRADE_REQUIRED("client_upgrade_required", 426, "Client upgrade required"),
    PRECONDITION_REQUIRED("precondition_required", 428, "Precondition required"),
    RATE_LIMITED("rate_limited", 429, "Rate limited"),
    INTERNAL_ERROR("internal_error", 500, "Internal error"),
    SERVICE_UNAVAILABLE("service_unavailable", 503, "Service unavailable"),
    SERVICE_BUSY("service_busy", 503, "Service busy"),
}

/** One failed field rule in a `validation_failed` or `password_rejected` problem. */
data class FieldError(
    /** A JSON Pointer into the body, or `query.<name>` / `header.<name>`. */
    val field: String,
    val code: String,
    /** English, for developers. Never personal data. */
    val message: String? = null,
)

/**
 * A failure the client is meant to see. Services throw or return one, and one StatusPages mapping
 * turns it into problem+json (core-and-auth §3). [detail] is English for developers and must never
 * hold personal data or internals.
 *
 * It carries no stack trace: it's an expected outcome, not a bug.
 */
sealed class DomainError(
    val code: ErrorCode,
    val detail: String? = null,
    val errors: List<FieldError> = emptyList(),
    /** Sent as `Retry-After`, in seconds. Every 429 and 503 sets it (core-and-auth §2). */
    val retryAfterSeconds: Long? = null,
) : RuntimeException(code.value, null, false, false) {
    class MalformedRequest(
        detail: String? = null,
    ) : DomainError(ErrorCode.MALFORMED_REQUEST, detail)

    class ValidationFailed(
        errors: List<FieldError>,
    ) : DomainError(ErrorCode.VALIDATION_FAILED, errors = errors) {
        init {
            require(errors.isNotEmpty()) { "validation_failed needs at least one field error" }
        }
    }

    class IdempotencyKeyRequired : DomainError(ErrorCode.IDEMPOTENCY_KEY_REQUIRED)

    /** The caller's role lacks the operation. Checked without looking at the object (§3). */
    class Forbidden : DomainError(ErrorCode.FORBIDDEN)

    class NotFound : DomainError(ErrorCode.NOT_FOUND)

    /** Another request with the same `Idempotency-Key` is still running (§4). */
    class IdempotencyRequestInProgress : DomainError(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS)

    /** `If-Match` doesn't name the current version (§5). */
    class PreconditionFailed : DomainError(ErrorCode.PRECONDITION_FAILED)

    class PayloadTooLarge : DomainError(ErrorCode.PAYLOAD_TOO_LARGE)

    class UnsupportedMediaType : DomainError(ErrorCode.UNSUPPORTED_MEDIA_TYPE)

    /** The same `Idempotency-Key` came with a different request (§4). */
    class IdempotencyKeyReused : DomainError(ErrorCode.IDEMPOTENCY_KEY_REUSED)

    class ClientUpgradeRequired : DomainError(ErrorCode.CLIENT_UPGRADE_REQUIRED)

    /** A versioned resource was changed without `If-Match` (§5). */
    class PreconditionRequired : DomainError(ErrorCode.PRECONDITION_REQUIRED)

    /** The database is unreachable or timed out (architecture §10). */
    class ServiceUnavailable(
        retryAfterSeconds: Long = DEFAULT_RETRY_AFTER_SECONDS,
    ) : DomainError(ErrorCode.SERVICE_UNAVAILABLE, retryAfterSeconds = retryAfterSeconds)

    companion object {
        const val DEFAULT_RETRY_AFTER_SECONDS = 2L
    }
}
