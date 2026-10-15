package spike

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class Role { MANAGER, WAITER, KITCHEN }

/** A staff member as `staff_members` would hold it. Platform admins have no restaurant or role. */
class Account(
    val id: String,
    val email: String,
    val passwordPhc: String,
    val restaurant: String?,
    val role: Role?,
    val platformAdmin: Boolean,
    val active: Boolean,
    val totpSecret: ByteArray?,
)

/**
 * Sign-in, the admin TOTP step, refresh rotation and sign-out (architecture §7).
 *
 * State lives in memory here. In the service it lives in PostgreSQL, so the per-instance state is
 * only meaningful in the local tests; on Vercel, S-4 measures the stateless parts (hashing and
 * token checks). See the README.
 */
class AuthService(
    accounts: List<Account>,
    private val hashers: Map<String, PasswordHasher>,
    private val gate: HashGate,
    private val tokens: Tokens,
    private val clock: Clock,
    private val dummyPhc: String,
    private val random: SecureRandom = SecureRandom(),
) {
    private val byEmail = accounts.associateBy { it.email.lowercase() }
    private val byId = accounts.associateBy { it.id }
    private val failures = ConcurrentHashMap<String, MutableList<Instant>>()
    private val lockedUntil = ConcurrentHashMap<String, Instant>()
    private val lastTotpStep = ConcurrentHashMap<String, Long>()
    private val usedMfaTokens = ConcurrentHashMap.newKeySet<String>()
    private val refreshTokens = ConcurrentHashMap<String, RefreshRecord>()
    private val revokedFamilies = ConcurrentHashMap.newKeySet<String>()

    fun account(id: String): Account? = byId[id]

    /**
     * Unknown email, wrong password, inactive and locked accounts all get the same [SignIn.Refused],
     * and an unknown email still runs a hash, so neither the answer nor its timing tells them apart.
     */
    suspend fun signIn(email: String, password: String, hasherName: String): SignIn {
        val hasher = hashers[hasherName] ?: error("unknown hasher $hasherName")
        val account = byEmail[email.trim().lowercase()]
        val gated = gate.run { hasher.verify(password, account?.passwordPhc ?: dummyPhc) }
        val timing = HashTiming(hasher.name, gated.waitMs, gated.runMs)
        if (account == null) return SignIn.Refused(timing)
        if (isLocked(account.id) || !account.active) return SignIn.Refused(timing)
        if (!gated.value) {
            recordFailure(account.id)
            return SignIn.Refused(timing)
        }
        failures.remove(account.id)
        if (account.platformAdmin) return SignIn.MfaRequired(tokens.mfa(account.id), timing)
        return SignIn.Success(issue(account, Audience.STAFF, listOf("pwd"), family = newId()), timing)
    }

    /** The admin's second step: the MFA token from [signIn] plus a TOTP code. */
    fun completeMfa(mfaToken: String, code: String): TokenPair? {
        val payload = runCatching { tokens.verifier(Audience.MFA).verify(mfaToken) }.getOrNull() ?: return null
        val account = byId[payload.subject] ?: return null
        val secret = account.totpSecret ?: return null
        if (!account.active || !account.platformAdmin || isLocked(account.id)) return null
        if (!usedMfaTokens.add(payload.id)) return null
        val step = Totp.verify(secret, code, clock.instant().epochSecond, lastTotpStep[account.id])
        if (step == null) {
            recordFailure(account.id)
            return null
        }
        lastTotpStep[account.id] = step
        failures.remove(account.id)
        return issue(account, Audience.ADMIN, listOf("pwd", "otp"), family = newId())
    }

    /** Rotates the refresh token. A token used twice revokes its whole family: it was probably stolen. */
    fun refresh(refreshToken: String): TokenPair? {
        val record = refreshTokens[sha256(refreshToken)] ?: return null
        if (record.family in revokedFamilies) return null
        if (!record.markUsed()) {
            revokedFamilies.add(record.family)
            return null
        }
        if (clock.instant().isAfter(record.expiresAt)) return null
        val account = byId[record.accountId]?.takeIf { it.active } ?: return null
        return issue(account, record.audience, record.amr, record.family, record.expiresAt)
    }

    fun signOut(refreshToken: String) {
        refreshTokens[sha256(refreshToken)]?.let { revokedFamilies.add(it.family) }
    }

    private fun issue(account: Account, audience: String, amr: List<String>, family: String, expiresAt: Instant? = null): TokenPair {
        val refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        refreshTokens[sha256(refresh)] = RefreshRecord(
            accountId = account.id,
            family = family,
            audience = audience,
            amr = amr,
            expiresAt = expiresAt ?: clock.instant().plus(REFRESH_LIFETIME),
        )
        return TokenPair(tokens.access(account.id, audience, amr, family), refresh, Tokens.ACCESS_LIFETIME.seconds)
    }

    private fun isLocked(accountId: String): Boolean =
        lockedUntil[accountId]?.let { clock.instant().isBefore(it) } ?: false

    private fun recordFailure(accountId: String) {
        val now = clock.instant()
        val recent = failures.compute(accountId) { _, list ->
            (list ?: mutableListOf()).apply {
                removeAll { it.isBefore(now.minus(LOCK_WINDOW)) }
                add(now)
            }
        }!!
        if (recent.size >= MAX_FAILURES) {
            lockedUntil[accountId] = now.plus(LOCK_DURATION)
            failures.remove(accountId)
        }
    }

    private fun newId() = UUID.randomUUID().toString()

    companion object {
        const val MAX_FAILURES = 10
        val LOCK_WINDOW: Duration = Duration.ofMinutes(15)
        val LOCK_DURATION: Duration = Duration.ofMinutes(15)
        val REFRESH_LIFETIME: Duration = Duration.ofHours(12)

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

private class RefreshRecord(
    val accountId: String,
    val family: String,
    val audience: String,
    val amr: List<String>,
    val expiresAt: Instant,
) {
    @Volatile
    private var used = false

    /** True the first time only. */
    @Synchronized
    fun markUsed(): Boolean = if (used) false else true.also { used = true }
}

data class TokenPair(val accessToken: String, val refreshToken: String, val expiresIn: Long)

data class HashTiming(val hasher: String, val waitMs: Double, val hashMs: Double)

sealed interface SignIn {
    val timing: HashTiming

    data class Success(val tokens: TokenPair, override val timing: HashTiming) : SignIn
    data class MfaRequired(val mfaToken: String, override val timing: HashTiming) : SignIn
    data class Refused(override val timing: HashTiming) : SignIn
}
