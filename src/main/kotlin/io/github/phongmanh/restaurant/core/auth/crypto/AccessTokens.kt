package io.github.phongmanh.restaurant.core.auth.crypto

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.ECDSAKeyProvider
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Token audiences (architecture §7). */
enum class Audience(
    val value: String,
) {
    STAFF("staff"),
    ADMIN("admin"),
    ;

    companion object {
        fun of(value: String?): Audience? = entries.firstOrNull { it.value == value }
    }
}

/** What a verified access token says. Who the caller is still comes from the database (§6). */
data class AccessClaims(
    val staffId: UUID,
    val sessionId: UUID,
    val audience: Audience,
    val amr: List<String>,
)

/**
 * Signs and verifies the ES256 access tokens (architecture §7). Verification accepts ES256 only,
 * looks the key up by `kid`, and checks `iss`, `aud` and `exp` with a 30 s leeway.
 */
class AccessTokens(
    private val keys: EcKeySet,
    private val issuer: String,
    private val clock: Clock,
) {
    private val algorithm =
        Algorithm.ECDSA256(
            object : ECDSAKeyProvider {
                override fun getPublicKeyById(keyId: String?): ECPublicKey? = keys.publicKey(keyId)

                override fun getPrivateKey(): ECPrivateKey? = keys.signing.privateKey

                override fun getPrivateKeyId(): String = keys.signing.kid
            },
        )

    fun sign(claims: AccessClaims): String {
        val now = clock.instant()
        return JWT
            .create()
            .withIssuer(issuer)
            .withAudience(claims.audience.value)
            .withSubject(claims.staffId.toString())
            .withClaim("sid", claims.sessionId.toString())
            .withClaim("amr", claims.amr)
            .withIssuedAt(now)
            .withExpiresAt(now.plusSeconds(LIFETIME.inWholeSeconds))
            .sign(algorithm)
    }

    /**
     * The claims of a valid token of one of [audiences], or `null` for any failure: not a JWT,
     * another algorithm, an unknown `kid`, a bad signature, another issuer or audience, expired,
     * or claims missing.
     */
    @Suppress("SpreadOperator") // two strings at most
    fun verify(
        token: String,
        audiences: Set<Audience>,
    ): AccessClaims? {
        // The java.time.Clock overload of build() is only on BaseVerification, which require() returns.
        val verification =
            JWT
                .require(algorithm)
                .withIssuer(issuer)
                .withAnyOfAudience(*audiences.map { it.value }.toTypedArray()) // java-jwt takes varargs
                .acceptLeeway(LEEWAY_SECONDS) as JWTVerifier.BaseVerification
        val verifier = verification.build(clock)
        val jwt =
            try {
                verifier.verify(token)
            } catch (_: JWTVerificationException) {
                return null
            }
        return runCatching {
            AccessClaims(
                staffId = UUID.fromString(jwt.subject),
                sessionId = UUID.fromString(jwt.getClaim("sid").asString()),
                audience = Audience.of(jwt.audience.single())!!,
                amr = jwt.getClaim("amr").asList(String::class.java),
            )
        }.getOrNull()
    }

    companion object {
        /** ASSUMPTION-5, confirmed by the owner (§16 Q5). */
        val LIFETIME: Duration = 15.minutes
        const val LEEWAY_SECONDS = 30L
    }
}
