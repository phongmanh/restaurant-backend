package io.github.phongmanh.restaurant.support

import io.github.phongmanh.restaurant.core.application.RateLimitPolicy
import io.github.phongmanh.restaurant.core.auth.AuthLimits
import io.github.phongmanh.restaurant.core.auth.AuthSettings
import io.github.phongmanh.restaurant.core.auth.RateLimitSettings
import io.github.phongmanh.restaurant.core.auth.crypto.Argon2Settings
import io.github.phongmanh.restaurant.core.auth.crypto.EmailHmac
import io.github.phongmanh.restaurant.core.auth.crypto.SecretBox
import io.github.phongmanh.restaurant.core.auth.crypto.StaffKeySet
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Keys made fresh for each test run, in the formats the environment variables use (§13). */
object TestKeys {
    const val ISSUER = "https://api.example.test"
    const val COOKIE_PATH = "/api/v1/auth"

    fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** One key of a JWK set; with [withPrivate] it's the signing key. */
    fun jwk(
        kid: String,
        pair: KeyPair,
        withPrivate: Boolean,
    ): String {
        val pub = pair.public as ECPublicKey
        val parts =
            mutableListOf(
                "\"kty\":\"EC\"",
                "\"crv\":\"P-256\"",
                "\"kid\":\"$kid\"",
                "\"x\":\"${b64(pub.w.affineX)}\"",
                "\"y\":\"${b64(pub.w.affineY)}\"",
            )
        if (withPrivate) parts += "\"d\":\"${b64((pair.private as ECPrivateKey).s)}\""
        return parts.joinToString(",", "{", "}")
    }

    /** The current staff key (signs) and the previous one (verifies only). */
    val current: KeyPair = ecKeyPair()
    val previous: KeyPair = ecKeyPair()
    val staffKeysJson = """{"keys":[${jwk("staff-2", current, true)},${jwk("staff-1", previous, false)}]}"""

    val totpKeysJson: String =
        """{"current":"t1","keys":{"t1":"${Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))}"}}"""

    /** Cheaper Argon2id than production, so the suite stays fast; the code paths are the same. */
    val argon2 = Argon2Settings(memoryKib = 1024, iterations = 1, parallelism = 1, maxConcurrent = 4, slotWait = 2.seconds)

    val auth: AuthSettings by lazy {
        AuthSettings(
            issuer = ISSUER,
            staffKeys = StaffKeySet.parse(staffKeysJson),
            totpKeys = SecretBox.parse(totpKeysJson),
            emailHmac = EmailHmac(ByteArray(32).also(SecureRandom()::nextBytes)),
            cookiePath = COOKIE_PATH,
            argon2 = argon2,
        )
    }

    val rateLimits =
        RateLimitSettings(
            upstash = null,
            timeout = 200.milliseconds,
            policies =
                listOf(
                    RateLimitPolicy(AuthLimits.SIGN_IN, 30, 60.seconds),
                    RateLimitPolicy(AuthLimits.MFA, 10, 60.seconds),
                    RateLimitPolicy(AuthLimits.REFRESH, 120, 60.seconds),
                    RateLimitPolicy(AuthLimits.LINK, 10, 60.seconds),
                    RateLimitPolicy(AuthLimits.PASSWORD_CHANGE, 5, 900.seconds),
                ).associateBy { it.name },
        )

    private fun b64(n: BigInteger): String {
        val bytes = n.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/** A clock a test can move. */
class MutableClock(
    var now: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS),
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    fun advance(by: Duration) {
        now = now.plus(by.toJavaDuration())
    }
}
