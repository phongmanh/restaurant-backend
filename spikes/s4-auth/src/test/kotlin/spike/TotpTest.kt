package spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TotpTest {
    // RFC 6238 appendix B, SHA-1, 8 digits.
    private val rfcSecret = "12345678901234567890".toByteArray()

    @Test
    fun `matches the RFC 6238 test vectors`() {
        mapOf(
            59L to "94287082",
            1111111109L to "07081804",
            1111111111L to "14050471",
            1234567890L to "89005924",
            2000000000L to "69279037",
            20000000000L to "65353130",
        ).forEach { (time, expected) ->
            assertEquals(expected, Totp.code(rfcSecret, Totp.step(time), digits = 8), "T=$time")
        }
    }

    @Test
    fun `accepts one step of drift either side and no more`() {
        val now = 1_800_000_000L
        val step = Totp.step(now)
        for (offset in -1L..1L) {
            assertEquals(step + offset, Totp.verify(rfcSecret, Totp.code(rfcSecret, step + offset), now, null))
        }
        assertNull(Totp.verify(rfcSecret, Totp.code(rfcSecret, step - 2), now, null))
        assertNull(Totp.verify(rfcSecret, Totp.code(rfcSecret, step + 2), now, null))
    }

    @Test
    fun `refuses a step already used, and malformed codes`() {
        val now = 1_800_000_000L
        val step = Totp.step(now)
        val code = Totp.code(rfcSecret, step)
        assertNull(Totp.verify(rfcSecret, code, now, lastUsedStep = step))
        assertNull(Totp.verify(rfcSecret, "12345", now, null))
        assertNull(Totp.verify(rfcSecret, "12a456", now, null))
    }
}
