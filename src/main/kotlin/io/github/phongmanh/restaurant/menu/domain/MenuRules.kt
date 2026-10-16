package io.github.phongmanh.restaurant.menu.domain

/** The size limits of menu §3 (ASSUMPTION-4, ASSUMPTION-13). Past one → `409 menu_limit_reached`. */
object MenuLimits {
    const val CATEGORIES = 50
    const val ITEMS = 1_000
    const val GROUPS_PER_ITEM = 10
    const val OPTIONS_PER_GROUP = 20
    const val OPTIONS = 5_000
}

/** The field rules of menu §5.4. */
object MenuRules {
    const val MAX_NAME = 100
    const val MAX_DESCRIPTION = 500
    const val MAX_PRICE = 100_000_000L
    const val CURRENCY = "VND"

    /** A name after trimming: 1 to 100 characters, as PostgreSQL's `char_length` counts them (code points). */
    fun name(value: String): Boolean = value.isNotEmpty() && length(value) <= MAX_NAME

    /** An optional English name after trimming: `""` (remove it) or a valid name. */
    fun optionalName(value: String): Boolean = value.isEmpty() || name(value)

    fun description(value: String): Boolean = length(value) <= MAX_DESCRIPTION

    /**
     * A Money amount in whole đồng (CLAUDE.md, wire formats) within 0 – 100,000,000, or `null`.
     * A sign, a fraction or anything but digits is refused.
     */
    fun amount(value: String): Long? =
        value
            .takeIf { it.length in 1..MAX_AMOUNT_DIGITS && it.all { c -> c in '0'..'9' } }
            ?.toLong()
            ?.takeIf { it <= MAX_PRICE }

    private fun length(value: String) = value.codePointCount(0, value.length)

    private const val MAX_AMOUNT_DIGITS = 15
}

/** The languages a menu is read in (FR-024). */
object MenuLanguages {
    const val VI = "vi"
    const val EN = "en"

    /**
     * The language of a guest's read (menu §4.1): [requested] if the restaurant offers it, the
     * restaurant's first language when none is asked for, and otherwise `vi`.
     */
    fun resolve(
        requested: String?,
        offered: List<String>,
    ): String =
        when {
            requested == null -> offered.firstOrNull() ?: VI
            requested in offered -> requested
            else -> VI
        }

    /** The text in [lang], falling back to Vietnamese field by field (§4.1). */
    fun pick(
        lang: String,
        vi: String,
        en: String?,
    ): String = if (lang == EN && en != null) en else vi

    fun pickOptional(
        lang: String,
        vi: String?,
        en: String?,
    ): String? = if (lang == EN && en != null) en else vi
}
