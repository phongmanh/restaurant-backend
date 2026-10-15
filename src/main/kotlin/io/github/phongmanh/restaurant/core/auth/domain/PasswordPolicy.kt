package io.github.phongmanh.restaurant.core.auth.domain

import io.github.phongmanh.restaurant.core.domain.DomainError
import java.text.Normalizer
import java.util.Locale

/**
 * The rules for a new password (core-and-auth §8.6, ASVS 5.0 V6.2): 8 to 64 code points after NFC
 * normalisation, any character allowed, no composition rules and no truncation; not on the common
 * list, and not the email or the part before its `@`.
 *
 * [commonPasswords] is the bundled list: SecLists' `100k-most-used-passwords-NCSC.txt` (MIT
 * licence, commit 997dd86), NFC-normalised and cut to the entries of 8 to 64 code points, since the
 * length rule already refuses the rest.
 */
class PasswordPolicy(
    private val commonPasswords: Set<String>,
) {
    /**
     * The password as it is hashed (NFC), or throws `400 password_rejected` with the rule it broke
     * in `errors[0].code`. [field] is the JSON Pointer of the password in the request.
     */
    fun check(
        password: String,
        email: String,
        field: String,
    ): String {
        val normalised = normalise(password)
        val length = normalised.codePointCount(0, normalised.length)
        val lower = normalised.lowercase(Locale.ROOT)
        val reason =
            when {
                length < MIN_LENGTH -> "too_short"
                length > MAX_LENGTH -> "too_long"
                lower == email.lowercase(Locale.ROOT) || lower == email.substringBefore('@').lowercase(Locale.ROOT) -> "matches_email"
                normalised in commonPasswords || lower in commonPasswords -> "too_common"
                else -> null
            }
        if (reason != null) throw DomainError.PasswordRejected(field, reason)
        return normalised
    }

    companion object {
        const val MIN_LENGTH = 8
        const val MAX_LENGTH = 64

        /** Passwords are hashed and checked in NFC, so the same text typed on two devices matches. */
        fun normalise(password: String): String = Normalizer.normalize(password, Normalizer.Form.NFC)

        /** Loads the bundled list from the classpath. */
        fun bundled(): PasswordPolicy {
            val stream =
                PasswordPolicy::class.java.getResourceAsStream("/auth/common-passwords.txt") ?: error("common-password list missing")
            val list = stream.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.filter { it.isNotEmpty() }.toHashSet() }
            return PasswordPolicy(list)
        }
    }
}

/** Emails are compared trimmed and lower-cased (§8.1). */
fun normaliseEmail(email: String): String = email.trim().lowercase(Locale.ROOT)
