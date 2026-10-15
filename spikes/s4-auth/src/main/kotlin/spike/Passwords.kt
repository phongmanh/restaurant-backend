package spike

import com.password4j.Argon2Function
import com.password4j.Password
import com.password4j.types.Argon2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Architecture §7: OWASP's Argon2id parameters. */
object Argon2Settings {
    const val MEMORY_KIB = 19_456
    const val ITERATIONS = 2
    const val PARALLELISM = 1
    const val SALT_BYTES = 16
    const val HASH_BYTES = 32
}

/** Hashes and checks passwords as PHC strings (`$argon2id$v=19$m=…,t=…,p=…$salt$hash`). */
interface PasswordHasher {
    val name: String
    fun hash(password: String): String
    fun verify(password: String, phc: String): Boolean
}

/** Bouncy Castle's Argon2id. It has no PHC format of its own, so [Phc] does the encoding. */
class BouncyCastleArgon2(private val random: SecureRandom = SecureRandom()) : PasswordHasher {
    override val name = "bc"

    override fun hash(password: String): String {
        val salt = ByteArray(Argon2Settings.SALT_BYTES).also(random::nextBytes)
        val phc = Phc(Argon2Settings.MEMORY_KIB, Argon2Settings.ITERATIONS, Argon2Settings.PARALLELISM, salt, ByteArray(0))
        return phc.copy(hash = derive(password, phc, Argon2Settings.HASH_BYTES)).encode()
    }

    override fun verify(password: String, phc: String): Boolean {
        val stored = Phc.parse(phc) ?: return false
        return MessageDigest.isEqual(derive(password, stored, stored.hash.size), stored.hash)
    }

    private fun derive(password: String, phc: Phc, length: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(phc.memoryKib)
            .withIterations(phc.iterations)
            .withParallelism(phc.parallelism)
            .withSalt(phc.salt)
            .build()
        val out = ByteArray(length)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(password.toByteArray(Charsets.UTF_8), out)
        return out
    }
}

/** Password4j's Argon2id, which reads and writes PHC strings itself. */
class Password4jArgon2 : PasswordHasher {
    override val name = "p4j"

    private val function = Argon2Function.getInstance(
        Argon2Settings.MEMORY_KIB,
        Argon2Settings.ITERATIONS,
        Argon2Settings.PARALLELISM,
        Argon2Settings.HASH_BYTES,
        Argon2.ID,
        Argon2Function.ARGON2_VERSION_13,
    )

    override fun hash(password: String): String =
        Password.hash(password).addRandomSalt(Argon2Settings.SALT_BYTES).with(function).result

    override fun verify(password: String, phc: String): Boolean =
        runCatching { Password.check(password, phc).with(Argon2Function.getInstanceFromHash(phc)) }.getOrDefault(false)
}

/** An Argon2id PHC string. Salt and hash are Base64 without padding, as the PHC format says. */
data class Phc(val memoryKib: Int, val iterations: Int, val parallelism: Int, val salt: ByteArray, val hash: ByteArray) {
    fun encode(): String =
        "\$argon2id\$v=19\$m=$memoryKib,t=$iterations,p=$parallelism\$${B64.encodeToString(salt)}\$${B64.encodeToString(hash)}"

    companion object {
        private val B64 = Base64.getEncoder().withoutPadding()

        fun parse(value: String): Phc? {
            val parts = value.split('$')
            if (parts.size != 6 || parts[0].isNotEmpty() || parts[1] != "argon2id" || parts[2] != "v=19") return null
            val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toIntOrNull() }
            return runCatching {
                Phc(
                    memoryKib = params["m"] ?: return null,
                    iterations = params["t"] ?: return null,
                    parallelism = params["p"] ?: return null,
                    salt = Base64.getDecoder().decode(parts[4]),
                    hash = Base64.getDecoder().decode(parts[5]),
                )
            }.getOrNull()
        }
    }
}

/**
 * Caps the hashes running at once on this instance (architecture §7: at most 4), so a burst of
 * sign-ins can't take 19 MiB each without limit. Reports how long the caller waited for a slot.
 */
class HashGate(permits: Int) {
    private val semaphore = Semaphore(permits)

    suspend fun <T> run(block: () -> T): Gated<T> {
        val queuedAt = System.nanoTime()
        return semaphore.withPermit {
            val startedAt = System.nanoTime()
            val value = withContext(Dispatchers.Default) { block() }
            Gated(value, waitMs = millisBetween(queuedAt, startedAt), runMs = millisBetween(startedAt, System.nanoTime()))
        }
    }
}

data class Gated<T>(val value: T, val waitMs: Double, val runMs: Double)

fun millisBetween(startNanos: Long, endNanos: Long): Double = (endNanos - startNanos) / 1_000_000.0
