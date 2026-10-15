package io.github.phongmanh.restaurant.core.auth.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The opaque tokens the service hands out: refresh tokens, MFA tokens and invitation and reset
 * links (§8). Each is 256 random bits, sent as base64url, and stored only as its SHA-256 hash, so a
 * database leak doesn't hand out usable tokens.
 */
class SecureTokens(
    private val random: SecureRandom = SecureRandom(),
) {
    fun generate(): String = ENCODER.encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))

    companion object {
        private const val TOKEN_BYTES = 32
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        /** The contract caps every token field at 128 characters. */
        const val MAX_LENGTH = 128

        /** What the database stores and looks up: SHA-256 of the token's text. */
        fun hash(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    }
}

/**
 * HMAC-SHA-256 of a typed email, for the audit log of a sign-in with an unknown email (§12): the
 * same email gives the same value, so repeated tries can be correlated without storing the email.
 */
class EmailHmac(
    private val key: ByteArray,
) {
    fun of(normalisedEmail: String): String =
        Mac
            .getInstance(ALGORITHM)
            .apply { init(SecretKeySpec(key, ALGORITHM)) }
            .doFinal(normalisedEmail.toByteArray(Charsets.UTF_8))
            .toHexString()

    override fun toString(): String = "EmailHmac(***)"

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
