package io.github.phongmanh.restaurant.core.auth.crypto

import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 one-time codes as authenticator apps make them: HMAC-SHA1, 30 s steps, 6 digits
 * (architecture §7). Built on the JDK's HMAC; checked against the RFC's test vectors.
 */
object Totp {
    const val STEP_SECONDS = 30L
    const val DIGITS = 6
    private const val SECRET_BYTES = 20
    private const val ALGORITHM = "HmacSHA1"
    private const val LOW_NIBBLE = 0x0f
    private const val MODULUS = 1_000_000 // 10^DIGITS

    /** A new 160-bit secret. */
    fun newSecret(random: SecureRandom): ByteArray = ByteArray(SECRET_BYTES).also(random::nextBytes)

    fun step(epochSeconds: Long): Long = Math.floorDiv(epochSeconds, STEP_SECONDS)

    fun code(
        secret: ByteArray,
        step: Long,
    ): String {
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret, ALGORITHM)) }
        val h = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(step).array())
        // RFC 4226 §5.3 dynamic truncation: 31 bits from the offset the last nibble names.
        val offset = h.last().toInt() and LOW_NIBBLE
        val binary = ByteBuffer.wrap(h, offset, Int.SIZE_BYTES).int and Int.MAX_VALUE
        return (binary % MODULUS).toString().padStart(DIGITS, '0')
    }

    /**
     * The step [code] belongs to, or `null`. Accepts the current step and one either side (clock
     * drift), but never a step at or before [lastUsedStep], so a code can't be used twice (§8.2).
     */
    fun verify(
        secret: ByteArray,
        code: String,
        epochSeconds: Long,
        lastUsedStep: Long?,
    ): Long? {
        if (code.length != DIGITS || !code.all { it in '0'..'9' }) return null
        val now = step(epochSeconds)
        return (now - 1..now + 1).firstOrNull { candidate ->
            (lastUsedStep == null || candidate > lastUsedStep) &&
                MessageDigest.isEqual(code(secret, candidate).toByteArray(), code.toByteArray())
        }
    }

    /** The `otpauth://` URI an authenticator app reads from a QR code. */
    fun otpauthUri(
        issuer: String,
        account: String,
        secret: ByteArray,
    ): String {
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        return "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=${Base32.encode(secret)}&issuer=${enc(issuer)}" +
            "&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"
    }
}

/** RFC 4648 base32 without padding: how authenticator apps take a TOTP secret. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val BITS = 5
    private const val MASK = 0x1f
    private const val BYTE_MASK = 0xff

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl Byte.SIZE_BITS) or (b.toInt() and BYTE_MASK)
            bits += Byte.SIZE_BITS
            while (bits >= BITS) {
                out.append(ALPHABET[(buffer shr (bits - BITS)) and MASK])
                bits -= BITS
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (BITS - bits)) and MASK])
        return out.toString()
    }
}
