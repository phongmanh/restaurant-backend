package io.github.phongmanh.restaurant.core.http

import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.ErrorCode
import io.github.phongmanh.restaurant.core.domain.FieldError
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** RFC 9457 problem details, as `components.schemas.Problem` in the contract. */
@Serializable
data class Problem(
    val type: String,
    val title: String,
    val status: Int,
    val code: String,
    @SerialName("request_id") val requestId: String,
    val detail: String? = null,
    val errors: List<ProblemFieldError>? = null,
)

@Serializable
data class ProblemFieldError(
    val field: String,
    val code: String,
    val message: String? = null,
)

val ProblemJson: ContentType = ContentType("application", "problem+json")

private val log = LoggerFactory.getLogger("io.github.phongmanh.restaurant.core.http.ProblemDetails")

suspend fun ApplicationCall.respondProblem(
    code: ErrorCode,
    detail: String? = null,
    errors: List<FieldError> = emptyList(),
) {
    val problem =
        Problem(
            type = "/problems/${code.value}",
            title = code.title,
            status = code.status,
            code = code.value,
            requestId = callId ?: "",
            detail = detail,
            errors = errors.takeIf { it.isNotEmpty() }?.map { ProblemFieldError(it.field, it.code, it.message) },
        )
    respondText(ApiJson.encodeToString(problem), ProblemJson, HttpStatusCode.fromValue(code.status))
}

/**
 * The one mapping from failures to responses (core-and-auth §3). A [DomainError] becomes its
 * problem; anything unexpected becomes `500 internal_error`, with the stack trace in the log and
 * never in the response.
 */
fun Application.installProblemDetails() {
    install(StatusPages) {
        exception<DomainError> { call, error ->
            call.respondProblem(error.code, error.detail, error.errors)
        }
        // Ktor's own request failures. Their messages can echo the input, so none reaches the client.
        exception<BadRequestException> { call, _ -> call.respondProblem(ErrorCode.MALFORMED_REQUEST) }
        exception<UnsupportedMediaTypeException> { call, _ -> call.respondProblem(ErrorCode.UNSUPPORTED_MEDIA_TYPE) }
        // What ContentNegotiation throws when no converter reads the body's Content-Type.
        exception<CannotTransformContentToTypeException> { call, _ -> call.respondProblem(ErrorCode.UNSUPPORTED_MEDIA_TYPE) }
        exception<PayloadTooLargeException> { call, _ -> call.respondProblem(ErrorCode.PAYLOAD_TOO_LARGE) }
        exception<Throwable> { call, cause ->
            log.error("Unhandled exception", cause)
            call.respondProblem(ErrorCode.INTERNAL_ERROR)
        }
        // No route matched. A known path with another method is answered the same way, since the
        // catalogue has no 405 code.
        status(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.respondProblem(ErrorCode.NOT_FOUND)
        }
    }
}
