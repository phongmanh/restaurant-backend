package io.github.phongmanh.restaurant.core.auth.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for the TOTP secrets (architecture §7, core-and-auth §9). A stored value is
 * `key id length ‖ key id ‖ nonce ‖ ciphertext ‖ tag`, so a key can rotate: new secrets use the
 * current key, and old ones are read with the key their ID names. [aad] binds a value to its row
 * (the account ID), so a secret copied onto another account doesn't decrypt.
 */
class SecretBox(
    private val keys: Map<String, ByteArray>,
    private val currentKeyId: String,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(currentKeyId in keys) { "the current TOTP key isn't in the key set" }
        require(keys.values.all { it.size == KEY_BYTES }) { "every TOTP key must be 256 bits" }
        require(keys.keys.all { it.isNotEmpty() && it.toByteArray().size <= Byte.MAX_VALUE }) { "a TOTP key ID is 1 to 127 bytes" }
    }

    fun seal(
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val sealed = cipher(Cipher.ENCRYPT_MODE, keys.getValue(currentKeyId), nonce, aad).doFinal(plaintext)
        val kid = currentKeyId.toByteArray()
        return ByteBuffer
            .allocate(1 + kid.size + NONCE_BYTES + sealed.size)
            .put(kid.size.toByte())
            .put(kid)
            .put(nonce)
            .put(sealed)
            .array()
    }

    /** The plaintext; throws if the value was changed, belongs to another row, or names an unknown key. */
    fun open(
        value: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val buffer = ByteBuffer.wrap(value)
        val kid = ByteArray(buffer.get().toInt()).also(buffer::get).decodeToString()
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        val sealed = ByteArray(buffer.remaining()).also(buffer::get)
        val key = keys[kid] ?: error("unknown TOTP key ID")
        return cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(sealed)
    }

    override fun toString(): String = "SecretBox(current=$currentKeyId, keys=${keys.keys})"

    private fun cipher(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
    ) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        updateAAD(aad)
    }

    companion object {
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128

        /**
         * Reads `auth.totpEncryptionKeys`: `{"current": "<id>", "keys": {"<id>": "<base64 of 32 bytes>", …}}`.
         * Error messages never include a key.
         */
        fun parse(json: String): SecretBox {
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: error("TOTP_ENCRYPTION_KEYS isn't JSON")
            val current = root["current"]?.jsonPrimitive?.content ?: error("TOTP_ENCRYPTION_KEYS has no current key")
            val keys =
                root["keys"]?.jsonObject?.mapValues { (_, v) ->
                    runCatching { Base64.getDecoder().decode(v.jsonPrimitive.content) }.getOrNull() ?: error("a TOTP key isn't base64")
                } ?: error("TOTP_ENCRYPTION_KEYS has no keys")
            return SecretBox(keys, current)
        }
    }
}
