package io.github.phongmanh.restaurant.core.auth

import io.github.phongmanh.restaurant.core.application.RateLimitPolicy
import io.github.phongmanh.restaurant.core.auth.crypto.Argon2Settings
import io.github.phongmanh.restaurant.core.auth.crypto.EmailHmac
import io.github.phongmanh.restaurant.core.auth.crypto.SecretBox
import io.github.phongmanh.restaurant.core.auth.crypto.StaffKeySet
import io.github.phongmanh.restaurant.core.auth.persistence.LinkKind
import kotlin.time.Duration

/** Sign-in settings (core-and-auth §13). The keys are parsed at startup, so a bad one stops it. */
data class AuthSettings(
    /** The API's public URL: the tokens' `iss`. */
    val issuer: String,
    val staffKeys: StaffKeySet,
    val totpKeys: SecretBox,
    val emailHmac: EmailHmac,
    /** The path the browser sees through the same-origin rewrite, `/api/v1/auth` (§8.3). */
    val cookiePath: String,
    val argon2: Argon2Settings,
    val links: LinkUrls,
)

/**
 * Where the links of §8.5 open: the staff web app, or the admin web app for a platform admin. The
 * token goes in the URL fragment, so it never reaches a server log or a `Referer` header
 * (architecture §7).
 */
data class LinkUrls(
    val staffApp: String,
    val adminApp: String,
) {
    fun of(
        kind: LinkKind,
        platformAdmin: Boolean,
        token: String,
    ): String {
        val base = (if (platformAdmin) adminApp else staffApp).trimEnd('/')
        val page = if (kind == LinkKind.INVITATION) "invite" else "reset-password"
        return "$base/$page#$token"
    }
}

/**
 * Rate-limit settings (§11, ADR-0008). Without [upstash] the limits are counted in the instance's
 * memory, which is allowed only in development and tests (AppConfig checks it).
 */
data class RateLimitSettings(
    val upstash: UpstashSettings?,
    /** The longest a check may take before the request is served without it (S-1 Q12: 200 ms). */
    val timeout: Duration,
    /** By policy name, e.g. `auth.sign_in`. */
    val policies: Map<String, RateLimitPolicy>,
) {
    fun policy(name: String): RateLimitPolicy = policies[name] ?: error("no rate limit configured for $name")
}

class UpstashSettings(
    val restUrl: String,
    val token: String,
) {
    override fun toString(): String = "UpstashSettings(***)"
}

/** The policy names of this design (§11). */
object AuthLimits {
    const val SIGN_IN = "auth.sign_in"
    const val MFA = "auth.mfa"
    const val REFRESH = "auth.refresh"
    const val LINK = "auth.link"
    const val PASSWORD_CHANGE = "auth.password_change"
}
