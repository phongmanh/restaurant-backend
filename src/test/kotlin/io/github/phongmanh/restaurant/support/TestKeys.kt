package io.github.phongmanh.restaurant.support

import io.github.phongmanh.restaurant.core.application.RateLimitPolicy
import io.github.phongmanh.restaurant.core.auth.AuthLimits
import io.github.phongmanh.restaurant.core.auth.AuthSettings
import io.github.phongmanh.restaurant.core.auth.GuestSettings
import io.github.phongmanh.restaurant.core.auth.LinkUrls
import io.github.phongmanh.restaurant.core.auth.RateLimitSettings
import io.github.phongmanh.restaurant.core.auth.crypto.Argon2Settings
import io.github.phongmanh.restaurant.core.auth.crypto.EcKeySet
import io.github.phongmanh.restaurant.core.auth.crypto.EmailHmac
import io.github.phongmanh.restaurant.core.auth.crypto.SecretBox
import io.github.phongmanh.restaurant.orders.application.OrderLimitPolicies
import io.github.phongmanh.restaurant.realtime.application.RealtimeLimits
import io.github.phongmanh.restaurant.restaurants.application.StaffLimits
import io.github.phongmanh.restaurant.tables.application.TableLimits
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
    const val STAFF_APP = "https://staff.example.test"
    const val ADMIN_APP = "https://admin.example.test"
    const val CUSTOMER_APP = "https://order.example.test"
    const val CRON_SECRET = "test-cron-secret-0123456789"

    /** An Ably key in Ably's format, `<app>.<key id>:<secret>`; it signs token requests locally and never reaches Ably. */
    const val ABLY_KEY = "testapp.testkey:test-ably-secret-0123456789"

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

    /** The guest session keys (ADR-0006): one key, separate from the staff keys. */
    val guestKeysJson = """{"keys":[${jwk("guest-1", ecKeyPair(), true)}]}"""

    /** The QR keys (tables §2): key `2` signs, key `1` is an older key whose printed codes still work. */
    val qrCurrent: KeyPair = ecKeyPair()
    val qrPrevious: KeyPair = ecKeyPair()
    val qrKeysJson = """{"keys":[${jwk("2", qrCurrent, true)},${jwk("1", qrPrevious, false)}]}"""

    val guests: GuestSettings by lazy {
        GuestSettings(EcKeySet.parse(guestKeysJson, "GUEST_JWT_KEYS"), EcKeySet.parse(qrKeysJson, "QR_SIGNING_KEYS"), CUSTOMER_APP)
    }

    val totpKeysJson: String =
        """{"current":"t1","keys":{"t1":"${Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))}"}}"""

    /** Cheaper Argon2id than production, so the suite stays fast; the code paths are the same. */
    val argon2 = Argon2Settings(memoryKib = 1024, iterations = 1, parallelism = 1, maxConcurrent = 4, slotWait = 2.seconds)

    val auth: AuthSettings by lazy {
        AuthSettings(
            issuer = ISSUER,
            staffKeys = EcKeySet.parse(staffKeysJson, "STAFF_JWT_KEYS"),
            totpKeys = SecretBox.parse(totpKeysJson),
            emailHmac = EmailHmac(ByteArray(32).also(SecureRandom()::nextBytes)),
            cookiePath = COOKIE_PATH,
            argon2 = argon2,
            links = LinkUrls(STAFF_APP, ADMIN_APP),
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
                    RateLimitPolicy(StaffLimits.LINK, 30, 3600.seconds),
                    RateLimitPolicy(TableLimits.QR_DEVICE, 10, 60.seconds),
                    RateLimitPolicy(TableLimits.QR_TABLE, 30, 60.seconds),
                    RateLimitPolicy(TableLimits.QR_IP, 60, 60.seconds),
                    RateLimitPolicy(TableLimits.WAITER_CALL, 10, 3600.seconds),
                    RateLimitPolicy(OrderLimitPolicies.PLACE_SESSION, 10, 60.seconds),
                    RateLimitPolicy(OrderLimitPolicies.PLACE_RESTAURANT, 300, 60.seconds),
                    RateLimitPolicy(OrderLimitPolicies.PLACE_STAFF, 30, 60.seconds),
                    RateLimitPolicy(OrderLimitPolicies.CANCEL, 30, 60.seconds),
                    RateLimitPolicy(OrderLimitPolicies.STAFF_CHANGE, 240, 60.seconds),
                    RateLimitPolicy(RealtimeLimits.TOKEN_SESSION, 30, 3600.seconds),
                    RateLimitPolicy(RealtimeLimits.TOKEN_STAFF, 60, 3600.seconds),
                    RateLimitPolicy(RealtimeLimits.EVENTS_SESSION, 120, 60.seconds),
                    RateLimitPolicy(RealtimeLimits.EVENTS_STAFF, 240, 60.seconds),
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
