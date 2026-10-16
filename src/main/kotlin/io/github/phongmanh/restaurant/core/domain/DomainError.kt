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
    EMAIL_IN_USE("email_in_use", 409, "Email in use"),
    LAST_MANAGER("last_manager", 409, "Last manager"),
    LAST_ADMIN("last_admin", 409, "Last admin"),
    SELF_ACTION_NOT_ALLOWED("self_action_not_allowed", 409, "Not allowed on yourself"),
    MEMBER_STATE_CONFLICT("member_state_conflict", 409, "Member state conflict"),
    MEMBER_LIMIT_REACHED("member_limit_reached", 409, "Member limit reached"),
    DUPLICATE_NAME("duplicate_name", 409, "Duplicate name"),
    CATEGORY_NOT_EMPTY("category_not_empty", 409, "Category not empty"),
    MENU_LIMIT_REACHED("menu_limit_reached", 409, "Menu limit reached"),
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

    /** A new password breaks a rule of §8.6; [reason] is `errors[0].code`. */
    class PasswordRejected(
        field: String,
        reason: String,
    ) : DomainError(ErrorCode.PASSWORD_REJECTED, errors = listOf(FieldError(field, reason)))

    /** The token is missing, invalid or expired, or its session has ended (§6). */
    class Unauthenticated : DomainError(ErrorCode.UNAUTHENTICATED)

    /** Every sign-in, MFA or current-password failure, with one body so none reveals more (§8.1). */
    class InvalidCredentials : DomainError(ErrorCode.INVALID_CREDENTIALS)

    /** The caller's restaurant is suspended and the endpoint isn't allowed then (restaurants §6). */
    class RestaurantSuspended : DomainError(ErrorCode.RESTAURANT_SUSPENDED)

    /** An invitation or reset link that is unknown, used, replaced or expired: one code for all (§8.5). */
    class LinkInvalid : DomainError(ErrorCode.LINK_INVALID)

    /** Over a rate limit (§11). */
    class RateLimited(
        retryAfterSeconds: Long,
    ) : DomainError(ErrorCode.RATE_LIMITED, retryAfterSeconds = retryAfterSeconds)

    /** No password-hashing slot came free in time (§8.1). */
    class ServiceBusy : DomainError(ErrorCode.SERVICE_BUSY, retryAfterSeconds = 1)

    /** The caller's role lacks the operation. Checked without looking at the object (§3). */
    class Forbidden : DomainError(ErrorCode.FORBIDDEN)

    class NotFound : DomainError(ErrorCode.NOT_FOUND)

    /** Another request with the same `Idempotency-Key` is still running (§4). */
    class IdempotencyRequestInProgress : DomainError(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS)

    /** An account with that email exists, in any restaurant (restaurants §7, §15 Q8). */
    class EmailInUse : DomainError(ErrorCode.EMAIL_IN_USE)

    /** The change would leave the restaurant without an active manager (restaurants §7). */
    class LastManager : DomainError(ErrorCode.LAST_MANAGER)

    /** The change would leave no active platform admin (restaurants §8). */
    class LastAdmin : DomainError(ErrorCode.LAST_ADMIN)

    /** An admin deactivating or resetting their own account (restaurants §8). */
    class SelfActionNotAllowed : DomainError(ErrorCode.SELF_ACTION_NOT_ALLOWED)

    /** The account's status doesn't allow the operation, e.g. an invitation for an active member (restaurants §7). */
    class MemberStateConflict(
        detail: String,
    ) : DomainError(ErrorCode.MEMBER_STATE_CONFLICT, detail)

    /** The restaurant has 100 invited and active accounts (restaurants §7, ASSUMPTION-12). */
    class MemberLimitReached : DomainError(ErrorCode.MEMBER_LIMIT_REACHED)

    /** A category or item name already used in the same scope, ignoring case (menu §5.1, §5.2). */
    class DuplicateName : DomainError(ErrorCode.DUPLICATE_NAME)

    /** Deleting a category that still has items (menu §14 Q7). */
    class CategoryNotEmpty : DomainError(ErrorCode.CATEGORY_NOT_EMPTY)

    /** A menu size limit would be passed (menu §3). */
    class MenuLimitReached : DomainError(ErrorCode.MENU_LIMIT_REACHED)

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
