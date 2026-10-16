package io.github.phongmanh.restaurant.core.auth.crypto

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
import java.util.Base64

/** One P-256 key of a key set. [privateKey] is set only on the key that signs. */
class EcKey(
    val kid: String,
    val publicKey: ECPublicKey,
    val privateKey: ECPrivateKey?,
)

/**
 * A set of ES256 signing keys (core-and-auth §13): the current key, which signs, and older public
 * keys, which still verify, so a key can rotate with overlap. Each use has its own set, named by
 * [setting]: the staff and admin tokens (`STAFF_JWT_KEYS`), the guest session tokens
 * (`GUEST_JWT_KEYS`) and the QR codes (`QR_SIGNING_KEYS`; tables §2). A key of one set never
 * verifies another's tokens (ADR-0006, ADR-0011).
 */
class EcKeySet(
    val setting: String,
    val keys: List<EcKey>,
) {
    val signing: EcKey = keys.singleOrNull { it.privateKey != null } ?: error("$setting needs exactly one key with a private part")

    init {
        require(keys.map { it.kid }.toSet().size == keys.size) { "$setting has a key ID twice" }
    }

    fun publicKey(kid: String?): ECPublicKey? = keys.firstOrNull { it.kid == kid }?.publicKey

    override fun toString(): String = "EcKeySet($setting, kids=${keys.map { it.kid }})"

    companion object {
        /**
         * Reads a JWK set (RFC 7517) of EC P-256 keys: `{"keys": [{"kty": "EC", "crv": "P-256",
         * "kid", "x", "y", "d"?}, …]}`. Error messages name [setting], never key material.
         */
        fun parse(
            json: String,
            setting: String,
        ): EcKeySet {
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: error("$setting isn't JSON")
            val keys = root["keys"]?.jsonArray ?: error("$setting has no keys")
            return EcKeySet(setting, keys.map { parseKey(it.jsonObject, setting) })
        }

        private fun parseKey(
            jwk: JsonObject,
            setting: String,
        ): EcKey {
            fun field(name: String) = jwk[name]?.jsonPrimitive?.content

            check(field("kty") == "EC" && field("crv") == "P-256") { "$setting holds a key that isn't EC P-256" }
            val kid = field("kid")?.takeIf { it.isNotBlank() } ?: error("a key in $setting has no kid")

            fun number(name: String) =
                field(name)?.let { BigInteger(1, Base64.getUrlDecoder().decode(it)) } ?: error("key $kid of $setting has no $name")

            val factory = KeyFactory.getInstance("EC")
            val publicKey = factory.generatePublic(ECPublicKeySpec(ECPoint(number("x"), number("y")), P256)) as ECPublicKey
            val privateKey = field("d")?.let { factory.generatePrivate(ECPrivateKeySpec(number("d"), P256)) as ECPrivateKey }
            return EcKey(kid, publicKey, privateKey)
        }

        private val P256: ECParameterSpec =
            AlgorithmParameters
                .getInstance("EC")
                .apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
    }
}
