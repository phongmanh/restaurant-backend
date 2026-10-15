package io.github.phongmanh.restaurant.core.persistence

import java.net.URI

/**
 * A connection target for pgjdbc and Hikari: the JDBC URL without credentials, plus the user and
 * password. [toString] leaves the password out, so a target can be logged.
 */
class JdbcTarget(
    val jdbcUrl: String,
    val user: String,
    val password: String,
) {
    override fun toString(): String = "JdbcTarget($jdbcUrl, user=$user)"

    companion object {
        /**
         * Parses a libpq-style URL as Neon shows it,
         * `postgresql://user:password@host[:port]/db?sslmode=require&channel_binding=require`.
         * pgjdbc doesn't take that form, and names one parameter differently (`channelBinding`).
         * Ported from `spikes/s2-postgres`.
         */
        fun parse(url: String): JdbcTarget {
            val uri = URI(url)
            require(uri.scheme == "postgresql" || uri.scheme == "postgres") { "not a postgresql:// URL" }
            val userInfo = requireNotNull(uri.rawUserInfo) { "the URL has no user and password" }
            val user = decode(userInfo.substringBefore(':'))
            val password = decode(userInfo.substringAfter(':', missingDelimiterValue = ""))
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val params =
                uri.rawQuery
                    .orEmpty()
                    .split('&')
                    .filter { it.isNotEmpty() }
                    .map { it.substringBefore('=') to it.substringAfter('=', "") }
                    .map { (name, value) -> (JDBC_PARAMETER_NAMES[name] ?: name) to value }
            val query = if (params.isEmpty()) "" else params.joinToString("&", prefix = "?") { (n, v) -> "$n=$v" }
            return JdbcTarget("jdbc:postgresql://${uri.host}$port${uri.rawPath}$query", user, password)
        }

        private fun decode(s: String) = java.net.URLDecoder.decode(s, Charsets.UTF_8)

        private val JDBC_PARAMETER_NAMES = mapOf("channel_binding" to "channelBinding")
    }
}
