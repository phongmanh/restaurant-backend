package io.github.phongmanh.restaurant.core.http

import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.FieldError
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.header
import io.ktor.server.request.path

/** A `major.minor.patch` version, compared numerically. */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val FORMAT = Regex("([0-9]{1,9})\\.([0-9]{1,9})\\.([0-9]{1,9})")

        fun parse(s: String): SemVer? =
            FORMAT.matchEntire(s)?.destructured?.let { (major, minor, patch) -> SemVer(major.toInt(), minor.toInt(), patch.toInt()) }
    }
}

/** HTTP settings from `AppConfig` (core-and-auth §13). */
data class HttpSettings(
    /** The web apps' origins, for CORS. */
    val allowedOrigins: List<String>,
    /** The oldest version each app may use, e.g. `customer-android` → 1.0.0. */
    val minClientVersions: Map<String, SemVer>,
)

private val X_CLIENT_FORMAT = Regex("([a-z0-9-]{1,32})/(.+)")

/**
 * `X-Client: <app>/<semver>` (core-and-auth §2, NFR-COMPAT-01): an app below its configured minimum
 * gets `426 client_upgrade_required`. No header, or an app without a minimum, is served. A value
 * that doesn't match the contract's pattern → 400.
 */
internal fun clientVersionPlugin(minimums: Map<String, SemVer>) =
    createApplicationPlugin("ClientVersion") {
        onCall { call ->
            if (!call.request.path().startsWith("/v1/")) return@onCall
            val value = call.request.header(X_CLIENT_HEADER) ?: return@onCall
            val match = X_CLIENT_FORMAT.matchEntire(value)
            val version = match?.groupValues?.get(2)?.let(SemVer::parse)
            if (match == null || version == null) {
                throw DomainError.ValidationFailed(
                    listOf(FieldError("header.$X_CLIENT_HEADER", "invalid_format", "<app>/<major>.<minor>.<patch>")),
                )
            }
            val minimum = minimums[match.groupValues[1]] ?: return@onCall
            if (version < minimum) throw DomainError.ClientUpgradeRequired()
        }
    }
