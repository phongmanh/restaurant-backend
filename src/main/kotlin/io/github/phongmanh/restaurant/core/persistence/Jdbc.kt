package io.github.phongmanh.restaurant.core.persistence

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
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
            is ByteArray -> setBytes(index, value)
            is UUID -> setObject(index, value)
            is Instant -> setTimestamp(index, Timestamp.from(value))
            else -> error("unsupported parameter type ${value::class.simpleName}")
        }
    }
    return this
}

/** Runs the query and maps every row. */
internal fun <T> PreparedStatement.list(row: (ResultSet) -> T): List<T> =
    executeQuery().use { rs -> generateSequence { if (rs.next()) row(rs) else null }.toList() }

/** Runs the query and maps its first row, or returns `null` if there's none. */
internal fun <T> PreparedStatement.firstOrNull(row: (ResultSet) -> T): T? = executeQuery().use { rs -> if (rs.next()) row(rs) else null }
