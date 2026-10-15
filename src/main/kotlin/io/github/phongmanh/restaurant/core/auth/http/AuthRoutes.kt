package io.github.phongmanh.restaurant.core.auth.http

import io.github.phongmanh.restaurant.core.auth.AuthLimits
import io.github.phongmanh.restaurant.core.auth.application.AcceptResult
import io.github.phongmanh.restaurant.core.auth.application.LinkService
import io.github.phongmanh.restaurant.core.auth.application.MfaService
import io.github.phongmanh.restaurant.core.auth.application.PasswordService
import io.github.phongmanh.restaurant.core.auth.application.RefreshService
import io.github.phongmanh.restaurant.core.auth.application.SignInResult
import io.github.phongmanh.restaurant.core.auth.application.SignInService
import io.github.phongmanh.restaurant.core.auth.crypto.Audience
import io.github.phongmanh.restaurant.core.auth.crypto.SecureTokens
import io.github.phongmanh.restaurant.core.auth.http.Rules.Companion.validate
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.Rfc3339
import io.github.phongmanh.restaurant.core.http.CallAuthenticator
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.endpoint
import io.github.phongmanh.restaurant.core.http.requestMeta
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import java.util.Locale

/**
 * The routes of `/v1/auth` (core-and-auth §8, §10). None takes an `Idempotency-Key` (§10). Each
 * checks in the order of §3: rate limit and who the caller is, then the input, then the operation.
 */
class AuthRoutes(
    private val signIn: SignInService,
    private val mfa: MfaService,
    private val refresh: RefreshService,
    private val links: LinkService,
    private val passwords: PasswordService,
    private val authenticator: CallAuthenticator,
    private val limits: AuthRateLimits,
    private val cookies: RefreshCookies,
) {
    fun install(route: Route) {
        with(route) {
            endpoint(Endpoints.Auth.SIGN_IN) { signIn(call) }
            endpoint(Endpoints.Auth.COMPLETE_MFA) { completeMfa(call) }
            endpoint(Endpoints.Auth.REFRESH_TOKENS) { refreshTokens(call) }
            endpoint(Endpoints.Auth.SIGN_OUT) { signOut(call) }
            endpoint(Endpoints.Auth.INSPECT_LINK) { inspectLink(call) }
            endpoint(Endpoints.Auth.ACCEPT_INVITATION) { acceptInvitation(call) }
            endpoint(Endpoints.Auth.COMPLETE_PASSWORD_RESET) { completePasswordReset(call) }
            endpoint(Endpoints.Auth.CHANGE_PASSWORD) { changePassword(call) }
            endpoint(Endpoints.Auth.GET_ME) { getMe(call) }
        }
    }

    private suspend fun signIn(call: ApplicationCall) {
        limits.byIp(call, AuthLimits.SIGN_IN)
        val body = call.receive<SignInRequest>()
        validate {
            rule(body.email.length <= MAX_EMAIL && '@' in body.email, "/email", "invalid_email")
            rule(body.password.length in 1..MAX_PASSWORD, "/password", "invalid_length")
        }
        when (val result = signIn.signIn(body.email, body.password, call.requestMeta())) {
            is SignInResult.Tokens -> cookies.respondTokens(call, result.issued)
            is SignInResult.MfaRequired -> call.respond(MfaRequiredResponse(result.mfaToken, result.expiresInSeconds))
        }
    }

    private suspend fun completeMfa(call: ApplicationCall) {
        limits.byIp(call, AuthLimits.MFA)
        val body = call.receive<MfaRequest>()
        validate {
            rule(body.mfaToken.length <= SecureTokens.MAX_LENGTH, "/mfa_token", "too_long")
            rule(isTotpCode(body.code), "/code", "invalid_code")
        }
        cookies.respondTokens(call, mfa.complete(body.mfaToken, body.code, call.requestMeta()))
    }

    private suspend fun refreshTokens(call: ApplicationCall) {
        limits.byIp(call, AuthLimits.REFRESH)
        val audience = audienceOf(call.receive<AudienceRequest>())
        val issued =
            try {
                refresh.refresh(cookies.read(call, audience) ?: throw DomainError.Unauthenticated(), audience, call.requestMeta())
            } catch (e: DomainError.Unauthenticated) {
                cookies.clear(call, audience)
                throw e
            }
        cookies.respondTokens(call, issued)
    }

    private suspend fun signOut(call: ApplicationCall) {
        // Sign-out has no policy of its own in §11; it shares the refresh one, its nearest kin.
        limits.byIp(call, AuthLimits.REFRESH)
        val audience = audienceOf(call.receive<AudienceRequest>())
        refresh.signOut(cookies.read(call, audience), call.bearerToken(), call.requestMeta())
        cookies.clear(call, audience)
        call.respond(HttpStatusCode.NoContent)
    }

    private suspend fun inspectLink(call: ApplicationCall) {
        limits.byIp(call, AuthLimits.LINK)
        val body = call.receive<LinkTokenRequest>()
        validate { rule(body.token.length <= SecureTokens.MAX_LENGTH, "/token", "too_long") }
        val link = links.inspect(body.token)
        call.respond(
            LinkDetailsResponse(
                kind = link.kind.value,
                email = link.email,
                displayName = link.displayName,
                restaurantName = link.restaurantName,
                role = link.role?.name?.lowercase(Locale.ROOT),
                totpEnrolmentRequired = link.totpEnrolmentRequired,
            ),
        )
    }

    private suspend fun acceptInvitation(call: ApplicationCall) {
        limits.byIp(call, AuthLimits.LINK)
        val body = call.receive<AcceptInvitationRequest>()
        validate {
            rule(body.token.length <= SecureTokens.MAX_LENGTH, "/token", "too_long")
            rule(body.totpCode == null || isTotpCode(body.totpCode), "/totp_code", "invalid_code")
        }
        when (val result = links.acceptInvitation(body.token, body.password, body.totpCode, call.requestMeta())) {
            AcceptResult.Done -> call.respond(HttpStatusCode.NoContent)
            is AcceptResult.TotpSetup -> call.respond(TotpSetupResponse(TotpSetupResponse.TotpSetup(result.secret, result.otpauthUri)))
        }
    }

    private suspend fun completePasswordReset(call: ApplicationCall) {
        limits.byIp(call, AuthLimits.LINK)
        val body = call.receive<CompletePasswordResetRequest>()
        validate { rule(body.token.length <= SecureTokens.MAX_LENGTH, "/token", "too_long") }
        links.completePasswordReset(body.token, body.password, call.requestMeta())
        call.respond(HttpStatusCode.NoContent)
    }

    private suspend fun changePassword(call: ApplicationCall) {
        val caller = authenticator.authenticate(call, Endpoints.Auth.CHANGE_PASSWORD)
        limits.byCaller(caller, AuthLimits.PASSWORD_CHANGE)
        val body = call.receive<ChangePasswordRequest>()
        validate { rule(body.currentPassword.length in 1..MAX_PASSWORD, "/current_password", "invalid_length") }
        passwords.change(caller, body.currentPassword, body.newPassword, call.requestMeta())
        // Every session has ended, this one included, so its cookies go too.
        Audience.entries.forEach { cookies.clear(call, it) }
        call.respond(HttpStatusCode.NoContent)
    }

    private suspend fun getMe(call: ApplicationCall) {
        val me = passwords.me(authenticator.authenticate(call, Endpoints.Auth.GET_ME))
        call.respond(
            MeResponse(
                staffId = me.staffId.toString(),
                email = me.email,
                displayName = me.displayName,
                role = me.role?.name?.lowercase(Locale.ROOT),
                restaurant = me.restaurant?.let { (id, name) -> MeResponse.RestaurantRef(id.toString(), name) },
                platformAdmin = me.isPlatformAdmin,
                amr = me.amr,
                sessionExpiresAt = Rfc3339.format(me.sessionExpiresAt),
            ),
        )
    }

    private companion object {
        const val MAX_EMAIL = 254
        const val MAX_PASSWORD = 256
    }
}
