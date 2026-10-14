package spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordsTest {
    private val bc = BouncyCastleArgon2()
    private val p4j = Password4jArgon2()

    @Test
    fun `each library verifies its own hash and refuses a wrong password`() {
        for (hasher in listOf(bc, p4j)) {
            val phc = hasher.hash("pässwörd with ünicode 🙂")
            assertTrue(hasher.verify("pässwörd with ünicode 🙂", phc), hasher.name)
            assertFalse(hasher.verify("pässwörd with ünicode 🙃", phc), hasher.name)
        }
    }

    @Test
    fun `hashes use the architecture parameters in PHC format`() {
        for (hasher in listOf(bc, p4j)) {
            val phc = Phc.parse(hasher.hash("x"))!!
            assertEquals(listOf(19_456, 2, 1, 16, 32), listOf(phc.memoryKib, phc.iterations, phc.parallelism, phc.salt.size, phc.hash.size), hasher.name)
        }
    }

    @Test
    fun `the two libraries read each other's hashes`() {
        assertTrue(p4j.verify("interop", bc.hash("interop")))
        assertTrue(bc.verify("interop", p4j.hash("interop")))
    }

    @Test
    fun `the seed hashes match the seed password`() {
        assertTrue(bc.verify(Seeds.PASSWORD, Seeds.PASSWORD_PHC))
        assertTrue(p4j.verify(Seeds.PASSWORD, Seeds.PASSWORD_PHC))
        assertFalse(bc.verify(Seeds.PASSWORD, Seeds.DUMMY_PHC))
    }

    @Test
    fun `malformed hashes are refused, not thrown`() {
        for (bad in listOf("", "plain", "\$argon2i\$v=19\$m=19456,t=2,p=1\$AAAA\$AAAA", "\$argon2id\$v=19\$m=x,t=2,p=1\$AAAA\$AAAA")) {
            assertFalse(bc.verify("x", bad), bad)
            assertFalse(p4j.verify("x", bad), bad)
        }
    }
}
