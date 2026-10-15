package spike

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 time-based one-time codes, as authenticator apps make them: HMAC-SHA1, 30 s steps,
 * 6 digits. Built on the JDK's HMAC and checked against the RFC's test vectors (TotpTest).
 */
object Totp {
    const val STEP_SECONDS = 30L
    const val DIGITS = 6

    fun step(epochSeconds: Long): Long = Math.floorDiv(epochSeconds, STEP_SECONDS)

    fun code(secret: ByteArray, step: Long, digits: Int = DIGITS, algorithm: String = "HmacSHA1"): String {
        val mac = Mac.getInstance(algorithm).apply { init(SecretKeySpec(secret, algorithm)) }
        val h = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(step).array())
        val offset = h.last().toInt() and 0x0f
        val binary = ((h[offset].toInt() and 0x7f) shl 24) or
            ((h[offset + 1].toInt() and 0xff) shl 16) or
            ((h[offset + 2].toInt() and 0xff) shl 8) or
            (h[offset + 3].toInt() and 0xff)
        var modulus = 1
        repeat(digits) { modulus *= 10 }
        return (binary % modulus).toString().padStart(digits, '0')
    }

    /**
     * Returns the step [code] belongs to, or null. Accepts the current step and one either side
     * (clock drift), and never a step at or before [lastUsedStep], so a code can't be replayed.
     */
    fun verify(secret: ByteArray, code: String, epochSeconds: Long, lastUsedStep: Long?): Long? {
        if (code.length != DIGITS || !code.all(Char::isDigit)) return null
        val now = step(epochSeconds)
        return (now - 1..now + 1).firstOrNull { candidate ->
            (lastUsedStep == null || candidate > lastUsedStep) &&
                MessageDigest.isEqual(code(secret, candidate).toByteArray(), code.toByteArray())
        }
    }
}
