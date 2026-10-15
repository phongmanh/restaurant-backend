package io.github.phongmanh.restaurant.core.auth.crypto

import io.github.phongmanh.restaurant.core.domain.DomainError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration

/**
 * Argon2id settings (architecture §7, core-and-auth §13): OWASP's parameters, and how many hashes
 * may run at once on one instance, so a burst of sign-ins can't take 19 MiB each without limit.
 */
data class Argon2Settings(
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
    val maxConcurrent: Int,
    /** How long a sign-in waits for a free slot before it gets `503 service_busy`. */
    val slotWait: Duration,
)

/** An Argon2id PHC string, `$argon2id$v=19$m=…,t=…,p=…$salt$hash`. Salt and hash are Base64 without padding. */
class Phc(
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
    val salt: ByteArray,
    val hash: ByteArray,
) {
    fun encode(): String =
        "\$argon2id\$v=19\$m=$memoryKib,t=$iterations,p=$parallelism\$${B64.encodeToString(salt)}\$${B64.encodeToString(hash)}"

    companion object {
        private val B64 = Base64.getEncoder().withoutPadding()
        private const val PARTS = 6
        private const val PARAMS = 3
        private const val SALT = 4
        private const val HASH = 5

        /** The parsed string, or `null` if it isn't an Argon2id v19 PHC string. */
        fun parse(value: String): Phc? {
            val parts = value.split('$')
            val wellFormed = parts.size == PARTS && parts[0].isEmpty() && parts[1] == "argon2id" && parts[2] == "v=19"
            if (!wellFormed) return null
            val params = parts[PARAMS].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toIntOrNull() }
            return runCatching {
                Phc(
                    memoryKib = requireNotNull(params["m"]),
                    iterations = requireNotNull(params["t"]),
                    parallelism = requireNotNull(params["p"]),
                    salt = Base64.getDecoder().decode(parts[SALT]),
                    hash = Base64.getDecoder().decode(parts[HASH]),
                )
            }.getOrNull()
        }
    }
}

/** Hashes and checks passwords. Both calls are CPU-heavy and blocking. */
interface PasswordHasher {
    fun hash(password: String): String

    /** `false` for a wrong password and for a stored value that isn't a PHC string. */
    fun verify(
        password: String,
        phc: String,
    ): Boolean

    /** The stored hash uses other parameters than the current ones, so it's re-hashed at the next sign-in. */
    fun needsRehash(phc: String): Boolean
}

/** Argon2id from Bouncy Castle (chosen after S-4). Bouncy Castle has no PHC format, so [Phc] encodes it. */
class Argon2Hasher(
    private val settings: Argon2Settings,
    private val random: SecureRandom = SecureRandom(),
) : PasswordHasher {
    override fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val params = Phc(settings.memoryKib, settings.iterations, settings.parallelism, salt, ByteArray(0))
        return Phc(params.memoryKib, params.iterations, params.parallelism, salt, derive(password, params, HASH_BYTES)).encode()
    }

    override fun verify(
        password: String,
        phc: String,
    ): Boolean {
        val stored = Phc.parse(phc) ?: return false
        return MessageDigest.isEqual(derive(password, stored, stored.hash.size), stored.hash)
    }

    override fun needsRehash(phc: String): Boolean {
        val stored = Phc.parse(phc) ?: return true
        return stored.memoryKib != settings.memoryKib ||
            stored.iterations != settings.iterations ||
            stored.parallelism != settings.parallelism ||
            stored.hash.size != HASH_BYTES
    }

    private fun derive(
        password: String,
        phc: Phc,
        length: Int,
    ): ByteArray {
        val params =
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
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

    private companion object {
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
    }
}

/**
 * The hasher behind a fixed number of slots (§8.1). Every check takes a slot, waits at most
 * [Argon2Settings.slotWait] for one (else `503 service_busy`), and runs off the request threads.
 *
 * [verifyOrDummy] checks against a fixed dummy hash when there's no usable account, so an unknown
 * email costs the same time as a wrong password and response times don't reveal which emails exist.
 */
class PasswordHashing(
    private val hasher: PasswordHasher,
    private val settings: Argon2Settings,
) {
    private val slots = Semaphore(settings.maxConcurrent)
    private val dummyHash: String by lazy { hasher.hash("dummy password, never matched") }

    suspend fun hash(password: String): String = inSlot { hasher.hash(password) }

    /** Checks [password] against [phc], or against the dummy hash when [phc] is `null` (then always `false`). */
    suspend fun verifyOrDummy(
        password: String,
        phc: String?,
    ): Boolean =
        inSlot {
            val matched = hasher.verify(password, phc ?: dummyHash)
            matched && phc != null
        }

    fun needsRehash(phc: String): Boolean = hasher.needsRehash(phc)

    private suspend fun <T> inSlot(block: () -> T): T {
        val acquired = withTimeoutOrNull(settings.slotWait) { slots.acquire() } != null
        if (!acquired) throw DomainError.ServiceBusy()
        return try {
            withContext(Dispatchers.Default) { block() }
        } finally {
            slots.release()
        }
    }
}
