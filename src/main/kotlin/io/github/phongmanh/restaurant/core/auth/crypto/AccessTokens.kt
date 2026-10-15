package io.github.phongmanh.restaurant.core.auth.crypto

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.ECDSAKeyProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.time.Clock
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** One P-256 key of the staff key set. [privateKey] is set only on the key that signs. */
class StaffKey(
    val kid: String,
    val publicKey: ECPublicKey,
    val privateKey: ECPrivateKey?,
)

/**
 * The staff and admin signing keys (core-and-auth §13): the current key, which signs, and the
 * previous public key, which still verifies, so a key can rotate with overlap. The guest keys
 * (ADR-0006) are a separate set and never verify a staff token.
 */
class StaffKeySet(
    val keys: List<StaffKey>,
) {
    val signing: StaffKey = keys.singleOrNull { it.privateKey != null } ?: error("STAFF_JWT_KEYS needs exactly one key with a private part")

    init {
        require(keys.map { it.kid }.toSet().size == keys.size) { "STAFF_JWT_KEYS has a key ID twice" }
    }

    fun publicKey(kid: String?): ECPublicKey? = keys.firstOrNull { it.kid == kid }?.publicKey

    override fun toString(): String = "StaffKeySet(kids=${keys.map { it.kid }})"

    companion object {
        /**
         * Reads a JWK set (RFC 7517) of EC P-256 keys: `{"keys": [{"kty": "EC", "crv": "P-256",
         * "kid", "x", "y", "d"?}, …]}`. Error messages never include key material.
         */
        fun parse(json: String): StaffKeySet {
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: error("STAFF_JWT_KEYS isn't JSON")
            val keys = root["keys"]?.jsonArray ?: error("STAFF_JWT_KEYS has no keys")
            return StaffKeySet(keys.map { parseKey(it.jsonObject) })
        }

        private fun parseKey(jwk: JsonObject): StaffKey {
            fun field(name: String) = jwk[name]?.jsonPrimitive?.content

            check(field("kty") == "EC" && field("crv") == "P-256") { "STAFF_JWT_KEYS holds a key that isn't EC P-256" }
            val kid = field("kid")?.takeIf { it.isNotBlank() } ?: error("a key in STAFF_JWT_KEYS has no kid")

            fun number(name: String) =
                field(name)?.let { BigInteger(1, Base64.getUrlDecoder().decode(it)) } ?: error("key $kid has no $name")

            val factory = KeyFactory.getInstance("EC")
            val publicKey = factory.generatePublic(ECPublicKeySpec(ECPoint(number("x"), number("y")), P256)) as ECPublicKey
            val privateKey = field("d")?.let { factory.generatePrivate(ECPrivateKeySpec(number("d"), P256)) as ECPrivateKey }
            return StaffKey(kid, publicKey, privateKey)
        }

        private val P256: ECParameterSpec =
            AlgorithmParameters
                .getInstance("EC")
                .apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
    }
}

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
    private val keys: StaffKeySet,
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
