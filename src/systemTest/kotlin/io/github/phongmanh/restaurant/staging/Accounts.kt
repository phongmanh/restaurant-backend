package io.github.phongmanh.restaurant.staging

import io.github.phongmanh.restaurant.core.auth.crypto.Totp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64

/**
 * The staging accounts the seed made (test-plan §6), for the system tests and the owner's
 * acceptance run. The file holds passwords and the admin's TOTP secret, so it lives next to the
 * other secrets in `deploy/staging/.accounts.staging`: ignored by git, readable by the owner only,
 * and never printed.
 */
@Serializable
data class Accounts(
    val admin: AdminLogin,
    @SerialName("invited_admin") val invitedAdmin: Login,
    val restaurants: Map<String, SeededRestaurant>,
    /** What `generateHistory` made; `null` until it runs. */
    val history: History? = null,
) {
    fun write(file: File) {
        val tmp = File(file.path + ".tmp")
        tmp.delete()
        Files.createFile(tmp.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        tmp.writeText(format.encodeToString(serializer(), this))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val PATH = "deploy/staging/.accounts.staging"

        private val format = Json { prettyPrint = true }

        fun read(file: File): Accounts = format.decodeFromString(serializer(), file.readText())
    }
}

/** Restaurant L and the smaller restaurants of test-plan §6. [complete] is false while the run is still writing. */
@Serializable
data class History(
    val large: SeededRestaurant,
    @SerialName("small_ids") val smallIds: List<String>,
    val complete: Boolean,
)

@Serializable
data class Login(
    val email: String,
    val password: String? = null,
    @SerialName("staff_id") val staffId: String,
)

@Serializable
data class AdminLogin(
    val email: String,
    val password: String,
    @SerialName("staff_id") val staffId: String,
    /** Base32, as the enrolment answered it. */
    @SerialName("totp_secret") val totpSecret: String,
)

@Serializable
data class SeededRestaurant(
    val id: String,
    val name: String,
    val manager: Login,
    val waiters: List<Login> = emptyList(),
    val kitchen: Login? = null,
    /** Deactivated after it was set up. */
    val deactivated: Login? = null,
    /** Locked by failed sign-ins when the seed ran; the lock lifts after 15 minutes (architecture §7). */
    val locked: Login? = null,
    @SerialName("table_ids") val tableIds: List<String> = emptyList(),
    @SerialName("item_ids") val itemIds: List<String> = emptyList(),
)

/** Passwords for the seeded accounts: 24 random URL-safe characters. */
fun newPassword(random: SecureRandom): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(PASSWORD_BYTES).also(random::nextBytes))

private const val PASSWORD_BYTES = 18

/**
 * TOTP codes for the admin. A code is accepted once (core-and-auth §8.1), so each call waits for a
 * step no earlier call used.
 */
class Authenticator(
    base32Secret: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val secret = base32Decode(base32Secret)
    private var lastStep = -1L

    fun nextCode(): String {
        var step = Totp.step(clock.instant().epochSecond)
        while (step <= lastStep) {
            Thread.sleep(STEP_POLL_MS)
            step = Totp.step(clock.instant().epochSecond)
        }
        lastStep = step
        return Totp.code(secret, step)
    }

    private companion object {
        const val STEP_POLL_MS = 500L
    }
}

/** RFC 4648 Base32, as the service encodes TOTP secrets (`Base32.encode`); padding and case ignored. */
fun base32Decode(text: String): ByteArray {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val out = java.io.ByteArrayOutputStream()
    var buffer = 0
    var bits = 0
    text.trimEnd('=').uppercase().forEach { c ->
        val value = alphabet.indexOf(c)
        require(value >= 0) { "not Base32" }
        buffer = (buffer shl BITS_PER_CHAR) or value
        bits += BITS_PER_CHAR
        if (bits >= Byte.SIZE_BITS) {
            bits -= Byte.SIZE_BITS
            out.write((buffer shr bits) and BYTE_MASK)
        }
    }
    return out.toByteArray()
}

private const val BITS_PER_CHAR = 5
private const val BYTE_MASK = 0xff
