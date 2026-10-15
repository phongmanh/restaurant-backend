package io.github.phongmanh.restaurant.core.auth.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Request and response bodies of `/v1/auth`, as `components.schemas` in the contract names them.
 * Secrets in them (passwords, tokens) are never logged: toString() leaves them out.
 */

@Serializable
class SignInRequest(
    val email: String,
    val password: String,
) {
    override fun toString(): String = "SignInRequest(***)"
}

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    val audience: String,
    @SerialName("mfa_required") val mfaRequired: Boolean = false,
    @SerialName("token_type") val tokenType: String = "Bearer",
) {
    override fun toString(): String = "TokenResponse(audience=$audience)"
}

@Serializable
data class MfaRequiredResponse(
    @SerialName("mfa_token") val mfaToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("mfa_required") val mfaRequired: Boolean = true,
) {
    override fun toString(): String = "MfaRequiredResponse(***)"
}

@Serializable
class MfaRequest(
    @SerialName("mfa_token") val mfaToken: String,
    val code: String,
) {
    override fun toString(): String = "MfaRequest(***)"
}

@Serializable
data class AudienceRequest(
    val audience: String,
)

@Serializable
class LinkTokenRequest(
    val token: String,
) {
    override fun toString(): String = "LinkTokenRequest(***)"
}

@Serializable
data class LinkDetailsResponse(
    val kind: String,
    val email: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("restaurant_name") val restaurantName: String? = null,
    val role: String? = null,
    @SerialName("totp_enrolment_required") val totpEnrolmentRequired: Boolean,
)

@Serializable
class AcceptInvitationRequest(
    val token: String,
    val password: String,
    @SerialName("totp_code") val totpCode: String? = null,
) {
    override fun toString(): String = "AcceptInvitationRequest(***)"
}

@Serializable
data class TotpSetupResponse(
    @SerialName("totp_setup") val totpSetup: TotpSetup,
) {
    @Serializable
    data class TotpSetup(
        val secret: String,
        @SerialName("otpauth_uri") val otpauthUri: String,
    ) {
        override fun toString(): String = "TotpSetup(***)"
    }
}

@Serializable
class CompletePasswordResetRequest(
    val token: String,
    val password: String,
) {
    override fun toString(): String = "CompletePasswordResetRequest(***)"
}

@Serializable
class ChangePasswordRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
) {
    override fun toString(): String = "ChangePasswordRequest(***)"
}

@Serializable
data class MeResponse(
    @SerialName("staff_id") val staffId: String,
    val email: String,
    @SerialName("display_name") val displayName: String,
    val role: String? = null,
    val restaurant: RestaurantRef? = null,
    @SerialName("platform_admin") val platformAdmin: Boolean,
    val amr: List<String>,
    @SerialName("session_expires_at") val sessionExpiresAt: String,
) {
    @Serializable
    data class RestaurantRef(
        val id: String,
        val name: String,
    )
}
