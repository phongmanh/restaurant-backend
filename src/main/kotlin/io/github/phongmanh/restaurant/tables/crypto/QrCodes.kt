package io.github.phongmanh.restaurant.tables.crypto

import io.github.phongmanh.restaurant.core.auth.crypto.EcKeySet
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.TableId
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.UUID

/** What a QR code says, once its signature is verified (tables §2). The database check comes after. */
data class QrClaims(
    val restaurantId: RestaurantId,
    val tableId: TableId,
    val keyVersion: Int,
)

/**
 * The QR codes on the tables (tables §2, ADR-0006).
 *
 * **In plain words:** the code holds "restaurant R, table T, version 3" and a signature only this
 * server can make, so nobody can make up a code for another table. It holds no secret: the same
 * code can be made again from the table's row whenever it's needed.
 *
 * The token is `<base64url(payload)>.<base64url(signature)>`. The payload is 38 bytes: the format
 * byte `1`, the key ID byte, the restaurant ID (16), the table ID (16) and the table's
 * `qr_key_version` (uint32). The signature is ES256 over the payload, 64 bytes (`r ‖ s`), with the
 * QR key set, which is separate from the guest and staff keys. Key IDs are therefore numbers
 * 0–255; old public keys stay in the set while their codes are printed (§13 Q5).
 */
class QrCodes(
    private val keys: EcKeySet,
    customerAppUrl: String,
) {
    private val base = customerAppUrl.trimEnd('/')
    private val signingKid: Int = kidByte(keys.signing.kid)

    init {
        keys.keys.forEach { kidByte(it.kid) }
    }

    /** The printable URL of a table's current code: `<customer app>/t/<token>`. */
    fun url(
        restaurantId: RestaurantId,
        tableId: TableId,
        keyVersion: Int,
    ): String = "$base/t/${token(restaurantId, tableId, keyVersion)}"

    fun token(
        restaurantId: RestaurantId,
        tableId: TableId,
        keyVersion: Int,
    ): String {
        val payload =
            ByteBuffer
                .allocate(PAYLOAD_BYTES)
                .put(FORMAT)
                .put(signingKid.toByte())
                .put(bytes(restaurantId.value))
                .put(bytes(tableId.value))
                .putInt(keyVersion)
                .array()
        val signature =
            Signature.getInstance(ALGORITHM).run {
                initSign(keys.signing.privateKey)
                update(payload)
                sign()
            }
        return "${B64.encodeToString(payload)}.${B64.encodeToString(signature)}"
    }

    /**
     * The claims of a well-formed code with a valid signature by a known key, or `null` for any
     * failure, so every bad code looks the same to the caller (§2). No database work happens here.
     */
    fun verify(token: String): QrClaims? {
        val parts = token.takeIf { it.length <= MAX_TOKEN_CHARS }?.split('.')?.takeIf { it.size == 2 }
        val payload = parts?.let { decode(it[0]) }?.takeIf { it.size == PAYLOAD_BYTES && it[0] == FORMAT }
        val signature = parts?.let { decode(it[1]) }?.takeIf { it.size == SIGNATURE_BYTES }
        val publicKey =
            payload?.let {
                keys.publicKey(
                    java.lang.Byte
                        .toUnsignedInt(it[1])
                        .toString(),
                )
            }
        val verified = payload?.takeIf { publicKey != null && signature != null && signed(publicKey, it, signature) }
        return verified?.let(::claims)
    }

    private fun signed(
        publicKey: ECPublicKey,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean =
        try {
            Signature.getInstance(ALGORITHM).run {
                initVerify(publicKey)
                update(payload)
                verify(signature)
            }
        } catch (_: GeneralSecurityException) {
            false
        }

    /** The IDs and version after the format and key bytes; a version below 1 is never made. */
    private fun claims(payload: ByteArray): QrClaims? {
        val buffer = ByteBuffer.wrap(payload, 2, PAYLOAD_BYTES - 2)
        val restaurant = UUID(buffer.getLong(), buffer.getLong())
        val table = UUID(buffer.getLong(), buffer.getLong())
        return buffer.getInt().takeIf { it > 0 }?.let { QrClaims(RestaurantId(restaurant), TableId(table), it) }
    }

    override fun toString(): String = "QrCodes($keys)"

    private companion object {
        const val FORMAT: Byte = 1
        const val PAYLOAD_BYTES = 38
        const val SIGNATURE_BYTES = 64
        const val UUID_BYTES = 16
        const val MAX_KID = 255

        /** The contract's `maxLength` for `qr_token`. */
        const val MAX_TOKEN_CHARS = 256

        /** ECDSA P-256 with SHA-256, the signature as the fixed 64 bytes of JWS (RFC 7518 §3.4), not DER. */
        const val ALGORITHM = "SHA256withECDSAinP1363Format"

        val B64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        fun decode(part: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(part) }.getOrNull()

        fun bytes(id: UUID): ByteArray =
            ByteBuffer
                .allocate(UUID_BYTES)
                .putLong(id.mostSignificantBits)
                .putLong(id.leastSignificantBits)
                .array()

        fun kidByte(kid: String): Int =
            kid.toIntOrNull()?.takeIf { it in 0..MAX_KID && it.toString() == kid }
                ?: error("QR_SIGNING_KEYS key IDs must be numbers 0-$MAX_KID")
    }
}
