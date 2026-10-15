package io.github.phongmanh.restaurant.core.auth.crypto

import io.github.phongmanh.restaurant.core.auth.domain.PasswordPolicy
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.support.TestKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The crypto building blocks of core-and-auth §14, against published vectors where there are some. */
class CryptoTest {
    @Test
    fun `TOTP matches the RFC 6238 SHA-1 vectors`() {
        val secret = "12345678901234567890".toByteArray()
        // RFC 6238 appendix B gives 8 digits; authenticator apps show the last 6.
        val vectors =
            mapOf(
                59L to "287082",
                1111111109L to "081804",
                1111111111L to "050471",
                1234567890L to "005924",
                2000000000L to "279037",
            )
        for ((time, code) in vectors) assertEquals(code, Totp.code(secret, Totp.step(time)), "t=$time")
    }

    @Test
    fun `TOTP accepts one step of drift and never a used step`() {
        val secret = Totp.newSecret(SecureRandom())
        val now = 1_760_000_000L
        val step = Totp.step(now)
        assertEquals(step - 1, Totp.verify(secret, Totp.code(secret, step - 1), now, null))
        assertEquals(step + 1, Totp.verify(secret, Totp.code(secret, step + 1), now, null))
        assertNull(Totp.verify(secret, Totp.code(secret, step - 2), now, null), "two steps old")
        assertNull(Totp.verify(secret, Totp.code(secret, step), now, lastUsedStep = step), "already used")
        assertNull(Totp.verify(secret, "12345", now, null))
        assertNull(Totp.verify(secret, "12345a", now, null))
    }

    @Test
    fun `base32 matches RFC 4648 without padding`() {
        val vectors =
            mapOf(
                "" to "",
                "f" to "MY",
                "fo" to "MZXQ",
                "foo" to "MZXW6",
                "foob" to "MZXW6YQ",
                "fooba" to "MZXW6YTB",
                "foobar" to "MZXW6YTBOI",
            )
        for ((input, output) in vectors) assertEquals(output, Base32.encode(input.toByteArray()))
        assertTrue(
            Totp
                .otpauthUri(
                    "Restaurant Admin",
                    "a@b.example",
                    "foobar".toByteArray(),
                ).startsWith("otpauth://totp/Restaurant%20Admin:a%40b.example?secret=MZXW6YTBOI"),
        )
    }

    @Test
    fun `the secret box opens what it sealed, and nothing changed or moved`() {
        val box = SecretBox.parse(TestKeys.totpKeysJson)
        val secret = Totp.newSecret(SecureRandom())
        val sealed = box.seal(secret, "account-1".toByteArray())
        assertContentEquals(secret, box.open(sealed, "account-1".toByteArray()))
        assertFailsWith<AEADBadTagException>("another row") { box.open(sealed, "account-2".toByteArray()) }
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<AEADBadTagException>("changed") { box.open(tampered, "account-1".toByteArray()) }
    }

    @Test
    fun `a rotated secret box still opens values sealed with the old key`() {
        val old = ByteArray(32).also(SecureRandom()::nextBytes)
        val new = ByteArray(32).also(SecureRandom()::nextBytes)
        val before = SecretBox(mapOf("k1" to old), "k1").seal("secret".toByteArray(), "a".toByteArray())
        val after = SecretBox(mapOf("k1" to old, "k2" to new), "k2")
        assertEquals("secret", after.open(before, "a".toByteArray()).decodeToString())
        assertEquals("secret", after.open(after.seal("secret".toByteArray(), "a".toByteArray()), "a".toByteArray()).decodeToString())
        assertFailsWith<IllegalStateException> { SecretBox(mapOf("k2" to new), "k2").open(before, "a".toByteArray()) }
    }

    @Test
    fun `key settings are checked, and errors never echo key material`() {
        val keyJson = TestKeys.jwk("only", TestKeys.ecKeyPair(), withPrivate = false)
        val noSigner = assertFailsWith<IllegalStateException> { StaffKeySet.parse("""{"keys":[$keyJson]}""") }
        assertFalse(keyJson.substringAfter("\"x\":\"").take(20) in noSigner.message!!)
        assertFailsWith<IllegalStateException> { StaffKeySet.parse("not json") }
        assertFailsWith<IllegalStateException> { StaffKeySet.parse("""{"keys":[{"kty":"RSA","kid":"r"}]}""") }
        assertFailsWith<IllegalArgumentException> { SecretBox.parse("""{"current":"x","keys":{"x":"c2hvcnQ="}}""") }
        val keys = StaffKeySet.parse(TestKeys.staffKeysJson)
        assertEquals("staff-2", keys.signing.kid)
        assertNotNull(keys.publicKey("staff-1"))
        assertEquals("StaffKeySet(kids=[staff-2, staff-1])", keys.toString())
    }

    @Test
    fun `Argon2id hashes as a PHC string and spots older parameters`() {
        val hasher = Argon2Hasher(TestKeys.argon2)
        val phc = hasher.hash("phở bò")
        assertTrue(phc.startsWith("\$argon2id\$v=19\$m=1024,t=1,p=1\$"), phc)
        assertTrue(hasher.verify("phở bò", phc))
        assertFalse(hasher.verify("pho bo", phc))
        assertFalse(hasher.verify("phở bò", "not a phc string"))
        assertFalse(hasher.needsRehash(phc))
        assertTrue(Argon2Hasher(TestKeys.argon2.copy(memoryKib = 2048)).needsRehash(phc))
        assertNull(Phc.parse("\$argon2i\$v=19\$m=1,t=1,p=1\$AA\$AA"))
    }

    @Test
    fun `a full set of hash slots answers service_busy after the wait`() =
        runBlocking {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blocking =
                object : PasswordHasher {
                    override fun hash(password: String): String {
                        started.countDown()
                        release.await()
                        return "h"
                    }

                    override fun verify(
                        password: String,
                        phc: String,
                    ) = false

                    override fun needsRehash(phc: String) = false
                }
            val hashing = PasswordHashing(blocking, TestKeys.argon2.copy(maxConcurrent = 1, slotWait = 100.milliseconds))
            val busy = async(Dispatchers.Default) { hashing.hash("one") }
            started.await()
            val e = assertFailsWith<DomainError.ServiceBusy> { hashing.hash("two") }
            assertEquals(1L, e.retryAfterSeconds)
            release.countDown()
            listOf(busy).awaitAll()
            Unit
        }

    @Test
    fun `passwords compare in NFC`() {
        val policy = PasswordPolicy(emptySet())
        val decomposed = "phở bò ngon"
        assertEquals("phở bò ngon", policy.check(decomposed, "a@b.example", "/password"))
    }
}
