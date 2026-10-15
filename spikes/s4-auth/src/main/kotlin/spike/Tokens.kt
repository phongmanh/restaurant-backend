package spike

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.ECDSAKeyProvider
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Token audiences (architecture §7). `mfa` is the short-lived token between password and TOTP. */
object Audience {
    const val STAFF = "staff"
    const val ADMIN = "admin"
    const val MFA = "mfa"
}

/**
 * Issues and verifies the service's own ES256 tokens. Verification looks the key up by `kid` in
 * the staff key set (current and previous), so a key can rotate with overlap. The guest key is
 * deliberately not in the set.
 */
class Tokens(private val keys: SpikeKeys, private val issuer: String, private val clock: Clock) {
    private val staffKeyProvider = object : ECDSAKeyProvider {
        private val publicKeys = listOf(keys.staffCurrent, keys.staffPrevious).associate { it.kid to it.publicKey }
        override fun getPublicKeyById(keyId: String?): ECPublicKey? = publicKeys[keyId]
        override fun getPrivateKey(): ECPrivateKey = keys.staffCurrent.privateKey
        override fun getPrivateKeyId(): String = keys.staffCurrent.kid
    }
    private val staffAlgorithm = Algorithm.ECDSA256(staffKeyProvider)

    fun access(subject: String, audience: String, amr: List<String>, sessionId: String): String =
        build(subject, audience, amr, sessionId, clock.instant(), ACCESS_LIFETIME, staffAlgorithm)

    fun mfa(subject: String): String =
        build(subject, Audience.MFA, listOf("pwd"), UUID.randomUUID().toString(), clock.instant(), MFA_LIFETIME, staffAlgorithm)

    fun verifier(audience: String): JWTVerifier =
        JWT.require(staffAlgorithm).withIssuer(issuer).withAudience(audience).acceptLeeway(LEEWAY_SECONDS).build()

    /**
     * Spike only: tokens for the cases that the sign-in flow can't produce on purpose. Each one
     * must be refused (or, for the previous key, accepted). See `GET /spike/test-tokens`.
     */
    fun testCases(staffSubject: String, adminSubject: String): Map<String, String> {
        val now = clock.instant()
        val sid = "spike-test"
        fun algorithmOf(key: EcKey) = Algorithm.ECDSA256(key.publicKey, key.privateKey)
        return mapOf(
            "expired" to build(staffSubject, Audience.STAFF, listOf("pwd"), sid, now.minus(Duration.ofMinutes(30)), ACCESS_LIFETIME, staffAlgorithm),
            "previousKey" to build(staffSubject, Audience.STAFF, listOf("pwd"), sid, now, ACCESS_LIFETIME, algorithmOf(keys.staffPrevious), keys.staffPrevious.kid),
            "guestKey" to build(staffSubject, Audience.STAFF, listOf("pwd"), sid, now, ACCESS_LIFETIME, algorithmOf(keys.guest), keys.guest.kid),
            "wrongIssuer" to build(staffSubject, Audience.STAFF, listOf("pwd"), sid, now, ACCESS_LIFETIME, staffAlgorithm, issuer = "https://someone-else.example"),
            "mfaTokenAsAccess" to mfa(staffSubject),
            "adminWithoutOtp" to build(adminSubject, Audience.ADMIN, listOf("pwd"), sid, now, ACCESS_LIFETIME, staffAlgorithm),
            "staffValid" to build(staffSubject, Audience.STAFF, listOf("pwd"), sid, now, ACCESS_LIFETIME, staffAlgorithm),
        )
    }

    private fun build(
        subject: String,
        audience: String,
        amr: List<String>,
        sessionId: String,
        issuedAt: Instant,
        lifetime: Duration,
        algorithm: Algorithm,
        keyId: String? = null,
        issuer: String = this.issuer,
    ): String = JWT.create()
        .apply { if (keyId != null) withKeyId(keyId) }
        .withIssuer(issuer)
        .withAudience(audience)
        .withSubject(subject)
        .withClaim("sid", sessionId)
        .withClaim("amr", amr)
        .withJWTId(UUID.randomUUID().toString())
        .withIssuedAt(issuedAt)
        .withExpiresAt(issuedAt.plus(lifetime))
        .sign(algorithm)

    companion object {
        val ACCESS_LIFETIME: Duration = Duration.ofMinutes(15)
        val MFA_LIFETIME: Duration = Duration.ofMinutes(5)
        const val LEEWAY_SECONDS = 30L
    }
}
