package io.github.phongmanh.restaurant.core.config

import io.github.phongmanh.restaurant.core.http.HttpSettings
import io.github.phongmanh.restaurant.core.http.SemVer
import io.github.phongmanh.restaurant.core.persistence.DatabaseSettings
import io.github.phongmanh.restaurant.core.persistence.JdbcTarget
import io.ktor.server.config.ApplicationConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * All settings, from `application.yaml` with environment substitution (core-and-auth §13,
 * architecture §11). Secrets come from environment variables, never from the file.
 */
data class AppConfig(
    val environment: String,
    val database: DatabaseSettings,
    val http: HttpSettings,
) {
    companion object {
        const val PRODUCTION = "production"

        /**
         * Reads and checks the settings. The service refuses to start if a secret is missing, or if a
         * production config allows a `localhost` origin (§13). Error messages name the setting,
         * never its value.
         */
        fun load(config: ApplicationConfig): AppConfig {
            val missing = mutableListOf<String>()

            fun secret(
                path: String,
                env: String,
            ): String = config.propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() } ?: "".also { missing += env }

            fun int(path: String) = config.property(path).getString().toInt()

            fun millis(path: String): Duration =
                config
                    .property(path)
                    .getString()
                    .toLong()
                    .milliseconds

            val environment = config.property("app.environment").getString()
            val appUrl = secret("database.url", "DATABASE_URL")
            val platformUrl = secret("database.platformUrl", "DATABASE_URL_PLATFORM")
            check(missing.isEmpty()) { "missing settings: ${missing.joinToString()}" }

            val origins =
                config
                    .property("http.allowedOrigins")
                    .getString()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            check(environment != PRODUCTION || origins.none { "localhost" in it || "127.0.0.1" in it }) {
                "a production config can't allow a localhost origin"
            }
            val minVersions =
                config
                    .config("http.minClientVersions")
                    .toMap()
                    .mapValues { (app, v) -> requireNotNull(SemVer.parse(v.toString())) { "http.minClientVersions.$app isn't a version" } }

            return AppConfig(
                environment = environment,
                database =
                    DatabaseSettings(
                        app = JdbcTarget.parse(appUrl),
                        platform = JdbcTarget.parse(platformUrl),
                        maxPoolSize = int("database.maxPoolSize"),
                        platformMaxPoolSize = int("database.platformMaxPoolSize"),
                        connectionTimeout = millis("database.connectionTimeoutMs"),
                        statementTimeout = millis("database.statementTimeoutMs"),
                    ),
                http = HttpSettings(allowedOrigins = origins, minClientVersions = minVersions),
            )
        }
    }
}
