package io.github.phongmanh.restaurant.restaurants.domain

import io.github.phongmanh.restaurant.core.domain.RestaurantId
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

enum class RestaurantStatus {
    ACTIVE,
    SUSPENDED,
    ;

    val value: String get() = name.lowercase(Locale.ROOT)

    companion object {
        fun of(value: String): RestaurantStatus = valueOf(value.uppercase(Locale.ROOT))
    }
}

/** Personal data of the owner (requirements §5): only platform admins see or change it (restaurants §3). */
data class OwnerContact(
    val name: String?,
    val email: String?,
    val phone: String?,
) {
    override fun toString(): String = "OwnerContact(***)"
}

/** A row of `restaurants` (restaurants §2): the tenant. */
data class Restaurant(
    val id: RestaurantId,
    val name: String,
    val address: String,
    val timeZone: String,
    val currency: String,
    val languages: List<String>,
    val openingHours: OpeningHours,
    val ownerContact: OwnerContact?,
    val status: RestaurantStatus,
    val suspendedAt: Instant?,
    /** Free text an admin typed; admins only, never in a log or the audit log. */
    val suspensionReason: String?,
    val createdAt: Instant,
    val version: Int,
)

/** A new restaurant (restaurants §5). */
data class NewRestaurant(
    val name: String,
    val address: String,
    val timeZone: String,
    val languages: List<String>,
    val openingHours: OpeningHours,
    val ownerContact: OwnerContact?,
)

/** The profile fields an edit may change (§4.2); `null` leaves a field as it is. */
data class ProfileChanges(
    val name: String? = null,
    val address: String? = null,
    val timeZone: String? = null,
    val languages: List<String>? = null,
    val openingHours: OpeningHours? = null,
    /** Admins only (§3). Set → replaces the whole contact. */
    val ownerContact: OwnerContact? = null,
) {
    /** The changed fields' names as the contract spells them, for the audit entry (never their values). */
    val fieldNames: List<String>
        get() =
            listOfNotNull(
                name?.let { "name" },
                address?.let { "address" },
                timeZone?.let { "time_zone" },
                languages?.let { "languages" },
                openingHours?.let { "opening_hours" },
                ownerContact?.let { "owner_contact" },
            )
}

/** The field rules of restaurants §4.2. Each returns whether the value is acceptable. */
object ProfileRules {
    const val MAX_NAME = 100
    const val MAX_ADDRESS = 300
    const val MAX_SUSPENSION_REASON = 500
    const val MAX_CONTACT_NAME = 100
    const val DEFAULT_TIME_ZONE = "Asia/Ho_Chi_Minh"
    const val FALLBACK_LANGUAGE = "vi"
    val LANGUAGES = setOf("vi", "en")
    private const val MAX_TIME_ZONE = 64

    /** Not blank, and at most [max] characters as PostgreSQL's `char_length` counts them (code points). */
    fun text(
        value: String,
        max: Int,
    ): Boolean = value.isNotBlank() && value.codePointCount(0, value.length) <= max

    /** An IANA region ID the JDK knows, such as `Asia/Ho_Chi_Minh`; offsets like `UTC+7` are refused. */
    fun timeZone(value: String): Boolean = value.length <= MAX_TIME_ZONE && value in ZoneId.getAvailableZoneIds()

    /** A subset of [LANGUAGES] without repeats that includes `vi`, the fallback (FR-024). */
    fun languages(value: List<String>): Boolean =
        value.isNotEmpty() && value.all { it in LANGUAGES } && value.toSet().size == value.size && FALLBACK_LANGUAGE in value
}
