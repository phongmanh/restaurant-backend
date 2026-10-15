package io.github.phongmanh.restaurant.core.persistence

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/*
 * Small helpers for the SQL that Exposed's DSL can't say (ON CONFLICT DO NOTHING with a lock
 * timeout, FOR UPDATE on a head row, casts to inet and jsonb). Parameters are always bound, never
 * concatenated into the SQL.
 */

/** Binds [values] to the statement's `?`s in order. `null` is a typed-by-context SQL NULL. */
internal fun PreparedStatement.bind(vararg values: Any?): PreparedStatement {
    values.forEachIndexed { i, value ->
        val index = i + 1
        when (value) {
            null -> setNull(index, Types.OTHER)

            is String -> setString(index, value)

            is Int -> setInt(index, value)

            is Long -> setLong(index, value)

            is Boolean -> setBoolean(index, value)

            is ByteArray -> setBytes(index, value)

            is UUID -> setObject(index, value)

            // As timestamptz, typed: a bare timestamp parameter is "unknown" to PostgreSQL and reads as text in a CASE.
            is Instant -> setObject(index, value.atOffset(ZoneOffset.UTC))

            is List<*> -> setArray(index, connection.createArrayOf("text", value.map { it as String }.toTypedArray()))

            else -> error("unsupported parameter type ${value::class.simpleName}")
        }
    }
    return this
}

/** Runs the query and maps every row. */
internal fun <T> PreparedStatement.list(row: (ResultSet) -> T): List<T> =
    executeQuery().use { rs -> generateSequence { if (rs.next()) row(rs) else null }.toList() }

/** A nullable `timestamptz` column. */
internal fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()

/** A `uuid` column. */
internal fun ResultSet.uuid(column: String): UUID? = getObject(column, UUID::class.java)

/** A `text[]` column. */
internal fun ResultSet.strings(column: String): List<String> = (getArray(column)?.array as? Array<*>)?.map { it as String }.orEmpty()

/** Runs the query and maps its first row, or returns `null` if there's none. */
internal fun <T> PreparedStatement.firstOrNull(row: (ResultSet) -> T): T? = executeQuery().use { rs -> if (rs.next()) row(rs) else null }
