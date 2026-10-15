package io.github.phongmanh.restaurant.core.domain

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** The wire format for times: RFC 3339 in UTC with `Z` and exactly three fractional digits (§2). */
object Rfc3339 {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun format(instant: Instant): String = FORMAT.format(instant)

    /** [instant] at the precision the wire carries, so a stored time reads back unchanged. */
    fun truncate(instant: Instant): Instant = instant.truncatedTo(ChronoUnit.MILLIS)
}
