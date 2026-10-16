package io.github.phongmanh.restaurant.tables.crypto

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.ECDSAKeyProvider
import io.github.phongmanh.restaurant.core.auth.crypto.AccessTokens
import io.github.phongmanh.restaurant.core.auth.crypto.EcKeySet
import io.github.phongmanh.restaurant.core.domain.DeviceId
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** What a verified guest token says. Whether its session is still open comes from the database (§3.2). */
data class GuestClaims(
    val deviceId: DeviceId,
    val sessionId: TableSessionId,
    val restaurantId: RestaurantId,
)

/**
 * The table-session tokens (tables §3.2, ADR-0006): ES256 JWTs signed with the guest key set, `aud`
 * `guest`, `sub` the device, `sid` the session, `rid` the restaurant, valid for 6 h. There's no
 * table claim, because a session can move (FR-032). Verification accepts ES256 only, looks the key
 * up by `kid`, and checks `iss`, `aud` and `exp` with the staff tokens' 30 s leeway.
 */
class GuestTokens(
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

    fun sign(claims: GuestClaims): String {
        val now = clock.instant()
        return JWT
            .create()
            .withIssuer(issuer)
            .withAudience(AUDIENCE)
            .withSubject(claims.deviceId.toString())
            .withClaim("sid", claims.sessionId.toString())
            .withClaim("rid", claims.restaurantId.toString())
            .withIssuedAt(now)
            .withExpiresAt(now.plusSeconds(LIFETIME.inWholeSeconds))
            .sign(algorithm)
    }

    /** The claims of a valid guest token, or `null` for any failure (§3.2 step 1). */
    fun verify(token: String): GuestClaims? {
        // The java.time.Clock overload of build() is only on BaseVerification, which require() returns.
        val verification =
            JWT
                .require(algorithm)
                .withIssuer(issuer)
                .withAudience(AUDIENCE)
                .acceptLeeway(AccessTokens.LEEWAY_SECONDS) as JWTVerifier.BaseVerification
        val jwt =
            try {
                verification.build(clock).verify(token)
            } catch (_: JWTVerificationException) {
                return null
            }
        return runCatching {
            GuestClaims(
                deviceId = DeviceId(UUID.fromString(jwt.subject)),
                sessionId = TableSessionId(UUID.fromString(jwt.getClaim("sid").asString())),
                restaurantId = RestaurantId(UUID.fromString(jwt.getClaim("rid").asString())),
            )
        }.getOrNull()
    }

    companion object {
        const val AUDIENCE = "guest"

        /** tables §3.2: after 6 h the guest scans again and gets a new token for the same session. */
        val LIFETIME: Duration = 6.hours
    }
}
