package io.github.phongmanh.restaurant.core.config

import io.github.phongmanh.restaurant.core.application.RateLimitPolicy
import io.github.phongmanh.restaurant.core.auth.AuthSettings
import io.github.phongmanh.restaurant.core.auth.GuestSettings
import io.github.phongmanh.restaurant.core.auth.LinkUrls
import io.github.phongmanh.restaurant.core.auth.RateLimitSettings
import io.github.phongmanh.restaurant.core.auth.UpstashSettings
import io.github.phongmanh.restaurant.core.auth.crypto.Argon2Settings
import io.github.phongmanh.restaurant.core.auth.crypto.EcKeySet
import io.github.phongmanh.restaurant.core.auth.crypto.EmailHmac
import io.github.phongmanh.restaurant.core.auth.crypto.SecretBox
import io.github.phongmanh.restaurant.core.http.HttpSettings
import io.github.phongmanh.restaurant.core.http.JobSettings
import io.github.phongmanh.restaurant.core.http.SemVer
import io.github.phongmanh.restaurant.core.persistence.DatabaseSettings
import io.github.phongmanh.restaurant.core.persistence.JdbcTarget
import io.ktor.server.config.ApplicationConfig
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * All settings, from `application.yaml` with environment substitution (core-and-auth §13,
 * architecture §11). Secrets come from environment variables, never from the file.
 */
data class AppConfig(
    val environment: String,
    val database: DatabaseSettings,
    val http: HttpSettings,
    val auth: AuthSettings,
    val guests: GuestSettings,
    val jobs: JobSettings,
    val rateLimits: RateLimitSettings,
    val realtime: RealtimeSettings,
) {
    companion object {
        const val PRODUCTION = "production"

        /** Where rate limits may be counted in memory, without Upstash: a single local instance. */
        private val LOCAL_ENVIRONMENTS = setOf("dev", "test")
        private const val MIN_HMAC_KEY_BYTES = 32

        /** `openssl rand -hex 16` makes one: 128 random bits, so the bearer can't be guessed. */
        private const val MIN_CRON_SECRET_CHARS = 32

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

            // Upstash and Ably may be left out in dev and test only.
            fun outsideDev(
                path: String,
                env: String,
            ): String =
                config.propertyOrNull(path)?.getString().orEmpty().also {
                    if (it.isBlank() &&
                        environment !in LOCAL_ENVIRONMENTS
                    ) {
                        missing += env
                    }
                }

            val appUrl = secret("database.url", "DATABASE_URL")
            val platformUrl = secret("database.platformUrl", "DATABASE_URL_PLATFORM")
            val jwtKeys = secret("auth.staffJwtKeys", "STAFF_JWT_KEYS")
            val totpKeys = secret("auth.totpEncryptionKeys", "TOTP_ENCRYPTION_KEYS")
            val hmacKey = secret("auth.emailHmacKey", "EMAIL_HMAC_KEY")
            val guestKeys = secret("guests.sessionJwtKeys", "GUEST_JWT_KEYS")
            val qrKeys = secret("guests.qrSigningKeys", "QR_SIGNING_KEYS")
            val cronSecret = secret("jobs.cronSecret", "CRON_SECRET")
            val upstashUrl = outsideDev("rateLimits.upstashUrl", "UPSTASH_REDIS_REST_URL")
            val upstashToken = outsideDev("rateLimits.upstashToken", "UPSTASH_REDIS_REST_TOKEN")
            val ablyKey = outsideDev("realtime.ablyApiKey", "ABLY_API_KEY")
            check(missing.isEmpty()) { "missing settings: ${missing.joinToString()}" }
            check(cronSecret.length >= MIN_CRON_SECRET_CHARS) { "CRON_SECRET must be at least $MIN_CRON_SECRET_CHARS characters" }

            val origins =
                config
                    .property("http.allowedOrigins")
                    .getString()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            check(environment != PRODUCTION || origins.none(::isLocal)) {
                "a production config can't allow a localhost origin"
            }
            val links = linkUrls(config, environment)
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
                auth = authSettings(config, jwtKeys, totpKeys, hmacKey, links),
                guests = guestSettings(config, environment, guestKeys, qrKeys),
                jobs = JobSettings(cronSecret),
                rateLimits =
                    RateLimitSettings(
                        upstash = if (upstashUrl.isBlank() || upstashToken.isBlank()) null else UpstashSettings(upstashUrl, upstashToken),
                        timeout = millis("rateLimits.timeoutMs"),
                        policies = policies(config.config("rateLimits.policies").toMap()),
                    ),
                realtime = RealtimeSettings(ablyKey.ifBlank { null }),
            )
        }

        private fun authSettings(
            config: ApplicationConfig,
            jwtKeys: String,
            totpKeys: String,
            hmacKey: String,
            links: LinkUrls,
        ): AuthSettings {
            fun int(path: String) = config.property(path).getString().toInt()
            return AuthSettings(
                issuer = config.property("auth.issuer").getString(),
                staffKeys = EcKeySet.parse(jwtKeys, "STAFF_JWT_KEYS"),
                totpKeys = SecretBox.parse(totpKeys),
                emailHmac = EmailHmac(decodeKey(hmacKey)),
                cookiePath = config.property("auth.cookiePath").getString(),
                links = links,
                argon2 =
                    Argon2Settings(
                        memoryKib = int("auth.argon2.memoryKib"),
                        iterations = int("auth.argon2.iterations"),
                        parallelism = int("auth.argon2.parallelism"),
                        maxConcurrent = int("auth.argon2.maxConcurrent"),
                        slotWait =
                            config
                                .property("auth.argon2.slotWaitMs")
                                .getString()
                                .toLong()
                                .milliseconds,
                    ),
            )
        }

        /** The guest keys and where printed QR codes open (tables §2); production needs https and no localhost. */
        private fun guestSettings(
            config: ApplicationConfig,
            environment: String,
            sessionKeys: String,
            qrKeys: String,
        ): GuestSettings {
            val customerApp = config.property("guests.customerAppUrl").getString()
            check(environment != PRODUCTION || !(isLocal(customerApp) || !customerApp.startsWith("https://"))) {
                "a production config needs an https customer app URL (guests.customerAppUrl)"
            }
            return GuestSettings(
                sessionKeys = EcKeySet.parse(sessionKeys, "GUEST_JWT_KEYS"),
                qrKeys = EcKeySet.parse(qrKeys, "QR_SIGNING_KEYS"),
                customerAppUrl = customerApp,
            )
        }

        /** Where links open (restaurants §5, §7); production needs https and no localhost. */
        private fun linkUrls(
            config: ApplicationConfig,
            environment: String,
        ): LinkUrls {
            val links = LinkUrls(config.property("auth.staffAppUrl").getString(), config.property("auth.adminAppUrl").getString())
            check(environment != PRODUCTION || listOf(links.staffApp, links.adminApp).none { isLocal(it) || !it.startsWith("https://") }) {
                "a production config needs https app URLs for links (auth.staffAppUrl, auth.adminAppUrl)"
            }
            return links
        }

        private fun isLocal(url: String) = "localhost" in url || "127.0.0.1" in url

        private fun decodeKey(base64: String): ByteArray {
            val key = runCatching { Base64.getDecoder().decode(base64.trim()) }.getOrNull() ?: error("EMAIL_HMAC_KEY isn't base64")
            check(key.size >= MIN_HMAC_KEY_BYTES) { "EMAIL_HMAC_KEY must be at least 256 bits" }
            return key
        }

        /**
         * The policies under `rateLimits.policies`, nested by the parts of their names (`auth:
         * sign_in: "30/60"` is `auth.sign_in`), each written as `<limit>/<window seconds>`.
         */
        private fun policies(
            tree: Map<String, Any?>,
            prefix: String = "",
        ): Map<String, RateLimitPolicy> =
            tree.entries
                .flatMap { (key, value) ->
                    val name = prefix + key
                    @Suppress("UNCHECKED_CAST")
                    when (value) {
                        is Map<*, *> -> {
                            policies(value as Map<String, Any?>, "$name.").entries.map { it.toPair() }
                        }

                        else -> {
                            val (limit, window) = value.toString().split('/').map { it.trim().toLong() }
                            listOf(name to RateLimitPolicy(name, limit, window.seconds))
                        }
                    }
                }.toMap()
    }
}
