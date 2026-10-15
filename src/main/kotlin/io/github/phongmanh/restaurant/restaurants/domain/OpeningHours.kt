package io.github.phongmanh.restaurant.restaurants.domain

import io.github.phongmanh.restaurant.core.domain.FieldError

/** One opening interval in the restaurant's local time, `HH:MM`. `closes` ≤ `opens` ends after midnight. */
data class OpeningInterval(
    val opens: String,
    val closes: String,
)

/**
 * A week of opening hours (restaurants §4.3): days `mon` … `sun`, each up to 3 intervals that don't
 * overlap. A missing or empty day is closed. Display only in release 1 (§15 Q5): nothing is blocked
 * outside these hours.
 */
data class OpeningHours(
    val days: Map<String, List<OpeningInterval>>,
) {
    companion object {
        val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        const val MAX_INTERVALS = 3
        val NONE = OpeningHours(emptyMap())
        private val TIME = Regex("([01][0-9]|2[0-3]):([0-5][0-9])")
        private const val MINUTES_PER_HOUR = 60
        private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

        /**
         * Checks [days] against §4.3 and returns them in a stable form (week order, each day's
         * intervals by opening time, empty days left out), or the broken rules, each at its JSON
         * Pointer under [pointer].
         */
        fun parse(
            days: Map<String, List<OpeningInterval>>,
            pointer: String,
        ): Pair<OpeningHours?, List<FieldError>> {
            val errors = mutableListOf<FieldError>()
            days.keys.filterNot { it in DAYS }.forEach { errors += FieldError("$pointer/$it", "unknown_day", "mon … sun") }
            for ((day, intervals) in days.filterKeys { it in DAYS }) {
                errors += dayErrors(intervals, "$pointer/$day")
            }
            if (errors.isNotEmpty()) return null to errors
            val normal =
                DAYS
                    .mapNotNull { day -> days[day]?.takeIf { it.isNotEmpty() }?.let { day to it.sortedBy(OpeningInterval::opens) } }
                    .toMap()
            return OpeningHours(normal) to emptyList()
        }

        private fun dayErrors(
            intervals: List<OpeningInterval>,
            pointer: String,
        ): List<FieldError> {
            if (intervals.size > MAX_INTERVALS) return listOf(FieldError(pointer, "too_many", "at most $MAX_INTERVALS intervals a day"))
            val errors = mutableListOf<FieldError>()
            intervals.forEachIndexed { i, interval ->
                if (minutes(interval.opens) == null) errors += FieldError("$pointer/$i/opens", "invalid_time", "HH:MM")
                if (minutes(interval.closes) == null) errors += FieldError("$pointer/$i/closes", "invalid_time", "HH:MM")
            }
            // Overlap is only checked once every time is valid.
            val overlapping = errors.isEmpty() && overlap(intervals)
            if (overlapping) errors += FieldError(pointer, "overlapping", "the intervals of a day may not overlap")
            return errors
        }

        /** Whether two intervals of one day share a minute; one that closes at or before it opens runs past midnight. */
        private fun overlap(intervals: List<OpeningInterval>): Boolean {
            val ranges =
                intervals
                    .map { interval ->
                        val opens = minutes(interval.opens)!!
                        val closes = minutes(interval.closes)!!
                        opens until (if (closes <= opens) closes + MINUTES_PER_DAY else closes)
                    }.sortedBy { it.first }
            return ranges.zipWithNext().any { (a, b) -> b.first <= a.last }
        }

        private fun minutes(time: String): Int? =
            TIME.matchEntire(time)?.destructured?.let { (h, m) -> h.toInt() * MINUTES_PER_HOUR + m.toInt() }
    }
}
