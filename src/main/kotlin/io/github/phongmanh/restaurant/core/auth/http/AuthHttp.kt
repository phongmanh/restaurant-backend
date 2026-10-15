package io.github.phongmanh.restaurant.core.auth.http

import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.RateLimits
import io.github.phongmanh.restaurant.core.auth.AuthSettings
import io.github.phongmanh.restaurant.core.auth.RateLimitSettings
import io.github.phongmanh.restaurant.core.auth.application.Accepts
import io.github.phongmanh.restaurant.core.auth.application.Authentication
import io.github.phongmanh.restaurant.core.auth.application.IssuedTokens
import io.github.phongmanh.restaurant.core.auth.crypto.AccessTokens
import io.github.phongmanh.restaurant.core.auth.crypto.Audience
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.FieldError
import io.github.phongmanh.restaurant.core.http.CallAuthenticator
import io.github.phongmanh.restaurant.core.http.Endpoint
import io.github.phongmanh.restaurant.core.http.RouteGroup
import io.github.phongmanh.restaurant.core.http.requestMeta
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import java.time.Clock
import java.time.Duration

/** Reads the bearer token and authenticates by the endpoint's route group (§2, §6). */
class BearerAuthenticator(
    private val authentication: Authentication,
) : CallAuthenticator {
    override suspend fun authenticate(
        call: ApplicationCall,
        endpoint: Endpoint,
    ): Caller {
        val accepts =
            when (endpoint.group) {
                RouteGroup.STAFF -> Accepts.STAFF

                RouteGroup.ADMIN -> Accepts.ADMIN

                RouteGroup.AUTH -> Accepts.STAFF_OR_ADMIN

                // Table-session tokens come with the tables feature (ADR-0006, step 6); until then, none passes.
                RouteGroup.GUEST -> throw DomainError.Unauthenticated()
            }
        return authentication.authenticate(call.bearerToken(), accepts, endpoint.allowedWhenSuspended)
    }
}

fun ApplicationCall.bearerToken(): String? =
    request
        .header(HttpHeaders.Authorization)
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring("Bearer ".length)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * The refresh cookie (§8.3): one name per audience, so a staff and an admin sign-in in one browser
 * don't overwrite each other; `HttpOnly; Secure; SameSite=Strict`, on the auth path the browser
 * sees through the web apps' rewrite. Clearing it sends the same attributes with `Max-Age=0`.
 */
class RefreshCookies(
    private val settings: AuthSettings,
    private val clock: Clock,
) {
    fun read(
        call: ApplicationCall,
        audience: Audience,
    ): String? = call.request.cookies[name(audience), CookieEncoding.RAW]?.takeIf { it.isNotEmpty() }

    /** Sets the cookie of [issued] and answers with its token response (§8.1). */
    suspend fun respondTokens(
        call: ApplicationCall,
        issued: IssuedTokens,
    ) {
        val maxAge = Duration.between(clock.instant(), issued.refreshExpiresAt).seconds.coerceAtLeast(0)
        set(call, issued.audience, issued.refreshToken, maxAge)
        call.respond(TokenResponse(issued.accessToken, AccessTokens.LIFETIME.inWholeSeconds, issued.audience.value))
    }

    fun clear(
        call: ApplicationCall,
        audience: Audience,
    ) = set(call, audience, "", 0)

    private fun set(
        call: ApplicationCall,
        audience: Audience,
        value: String,
        maxAgeSeconds: Long,
    ) {
        call.response.headers.append(
            HttpHeaders.SetCookie,
            "${name(audience)}=$value; Max-Age=$maxAgeSeconds; Path=${settings.cookiePath}; Secure; HttpOnly; SameSite=Strict",
        )
    }

    companion object {
        fun name(audience: Audience) = "__Secure-rt-${audience.value}"
    }
}

/** The limits of §11 by their subject: the client's IP, or the signed-in staff member. */
class AuthRateLimits(
    private val limits: RateLimits,
    private val settings: RateLimitSettings,
) {
    suspend fun byIp(
        call: ApplicationCall,
        policy: String,
    ) = limits.check(settings.policy(policy), call.requestMeta().ip ?: "unknown")

    suspend fun byCaller(
        caller: Caller,
        policy: String,
    ) {
        val subject =
            when (caller) {
                is Caller.Staff -> caller.staffId.toString()
                is Caller.Admin -> caller.staffId.toString()
                is Caller.Guest -> caller.deviceId.toString()
            }
        limits.check(settings.policy(policy), subject)
    }
}

/** Field rules of a request body: every broken rule is collected, then one `400 validation_failed`. */
class Rules {
    private val errors = mutableListOf<FieldError>()

    fun rule(
        ok: Boolean,
        field: String,
        code: String,
    ) {
        if (!ok) errors += FieldError(field, code)
    }

    companion object {
        fun validate(block: Rules.() -> Unit) {
            val errors = Rules().apply(block).errors
            if (errors.isNotEmpty()) throw DomainError.ValidationFailed(errors)
        }
    }
}

fun isTotpCode(code: String) = code.length == TOTP_DIGITS && code.all { it in '0'..'9' }

fun audienceOf(body: AudienceRequest): Audience =
    Audience.of(body.audience) ?: throw DomainError.ValidationFailed(listOf(FieldError("/audience", "invalid_value", "staff or admin")))

private const val TOTP_DIGITS = 6
