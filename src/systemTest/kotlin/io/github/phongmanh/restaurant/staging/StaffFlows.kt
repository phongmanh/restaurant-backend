package io.github.phongmanh.restaurant.staging

import io.github.phongmanh.restaurant.appModule
import io.github.phongmanh.restaurant.core.config.AppConfig
import io.github.phongmanh.restaurant.core.persistence.DatabasePools
import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.core.Koin
import org.koin.dsl.koinApplication

/** The staff sign-in steps (core-and-auth §8), through the deployed API. */
class StaffFlows(
    private val api: StagingApi,
) {
    /** The one-time token at the end of an invitation or reset link (`…/invite#<token>`). */
    fun tokenOf(invitation: JsonObject): String =
        invitation
            .getValue("link")
            .jsonPrimitive.content
            .substringAfter('#')

    /** A staff member sets a password through their invitation (204). */
    fun acceptInvitation(
        token: String,
        password: String,
    ) {
        api.expect(NO_CONTENT, "POST", "/v1/auth/invitations/accept", invitationBody(token, password))
    }

    /**
     * An admin's invitation: the first call answers the TOTP secret, the second confirms it with a
     * code (core-and-auth §8.2). Returns the Base32 secret.
     */
    fun acceptAdminInvitation(
        token: String,
        password: String,
    ): Pair<String, Authenticator> {
        val setup = api.expect(OK, "POST", "/v1/auth/invitations/accept", invitationBody(token, password))
        val secret =
            setup.json
                .getValue("totp_setup")
                .jsonObject
                .getValue("secret")
                .jsonPrimitive.content
        val authenticator = Authenticator(secret)
        api.expect(NO_CONTENT, "POST", "/v1/auth/invitations/accept", invitationBody(token, password, authenticator.nextCode()))
        return secret to authenticator
    }

    /**
     * Signs in, through the TOTP step when the account has one; returns the access token. A code is
     * accepted once, and another run may have used this step's code already, so a refused code is
     * tried once more, from a fresh sign-in, with the next step's code.
     */
    fun signIn(
        email: String,
        password: String,
        authenticator: Authenticator? = null,
    ): String {
        repeat(MFA_TRIES) {
            val first =
                api.expect(
                    OK,
                    "POST",
                    "/v1/auth/sign-in",
                    buildJsonObject {
                        put("email", email)
                        put("password", password)
                    },
                )
            if (first.json["mfa_required"] != JsonPrimitive(true)) return first.text("access_token")
            val codes = requireNotNull(authenticator) { "$email needs a TOTP code" }
            val mfa =
                api.call(
                    "POST",
                    "/v1/auth/mfa",
                    buildJsonObject {
                        put("mfa_token", first.text("mfa_token"))
                        put("code", codes.nextCode())
                    },
                )
            if (mfa.status == OK) return mfa.text("access_token")
            check(mfa.status == UNAUTHORIZED) { "POST /v1/auth/mfa answered ${mfa.status}" }
        }
        error("$email: the TOTP step refused $MFA_TRIES codes")
    }

    private fun invitationBody(
        token: String,
        password: String,
        code: String? = null,
    ) = buildJsonObject {
        put("token", token)
        put("password", password)
        code?.let { put("totp_code", it) }
    }

    companion object {
        const val OK = 200
        const val CREATED = 201
        const val NO_CONTENT = 204
        const val UNAUTHORIZED = 401
        private const val MFA_TRIES = 2
    }
}

/**
 * The service's own components, wired as the service wires them (the operator command does the
 * same, restaurants §5). For the steps no API offers: the first admin, and writing history rows.
 */
fun <T> withService(block: (Koin) -> T): T {
    val settings = AppConfig.load(ApplicationConfig("application.yaml"))
    return DatabasePools(settings.database).use { pools ->
        val app = koinApplication { modules(appModule(settings, pools)) }
        try {
            block(app.koin)
        } finally {
            app.close()
        }
    }
}
