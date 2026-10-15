package spike

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** One ES256 (P-256) key pair with its key ID. */
class EcKey(val kid: String, val privateKey: ECPrivateKey, val publicKey: ECPublicKey)

/**
 * The spike's signing keys: the current and previous staff keys (rotation with overlap) and a
 * guest key (ADR-0006), which must never verify a staff token.
 *
 * Every instance must hold the same keys, or a token from one instance fails on the next, so on
 * Vercel they come from the `SPIKE_KEYS` environment variable (scripts/setup-env.mjs). Locally,
 * without it, fresh keys are generated in memory.
 */
class SpikeKeys(val staffCurrent: EcKey, val staffPrevious: EcKey, val guest: EcKey) {
    companion object {
        fun fromEnv(value: String?): SpikeKeys = if (value.isNullOrBlank()) generate() else parse(value)

        /** [value] is Base64 of `{"staffCurrent":{kid,privateKey,publicKey},…}`, keys as Base64 DER. */
        fun parse(value: String): SpikeKeys {
            val json = Json.decodeFromString<KeysJson>(Base64.getDecoder().decode(value.trim()).decodeToString())
            return SpikeKeys(json.staffCurrent.toKey(), json.staffPrevious.toKey(), json.guest.toKey())
        }

        fun generate(): SpikeKeys = SpikeKeys(newKey("staff-local-2"), newKey("staff-local-1"), newKey("guest-local-1"))

        private fun newKey(kid: String): EcKey {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            return EcKey(kid, pair.private as ECPrivateKey, pair.public as ECPublicKey)
        }
    }
}

@Serializable
private class KeysJson(val staffCurrent: KeyJson, val staffPrevious: KeyJson, val guest: KeyJson)

@Serializable
private class KeyJson(val kid: String, val privateKey: String, val publicKey: String) {
    fun toKey(): EcKey {
        val factory = KeyFactory.getInstance("EC")
        val decoder = Base64.getDecoder()
        return EcKey(
            kid = kid,
            privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(decoder.decode(privateKey))) as ECPrivateKey,
            publicKey = factory.generatePublic(X509EncodedKeySpec(decoder.decode(publicKey))) as ECPublicKey,
        )
    }
}
