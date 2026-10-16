package io.github.phongmanh.restaurant.core.http

import io.github.phongmanh.restaurant.core.application.RetentionTask
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.ktor.http.HttpHeaders
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingHandler
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.get
import java.security.MessageDigest

/**
 * Declares a scheduled job (core-and-auth §2, the `/internal/jobs/` paths): Vercel Cron calls it with a GET
 * and `Authorization: Bearer <CRON_SECRET>`. Anything else → `401 unauthenticated`. The secret is
 * compared in constant time, through SHA-256 so both sides have the same length.
 */
fun Route.job(
    path: String,
    body: RoutingHandler,
): Route {
    require(path.startsWith(Endpoints.Jobs.PREFIX)) { "$path isn't a job path" }
    val expected = sha256(application.get<JobSettings>().cronSecret)
    return get(path) {
        val presented =
            call.request
                .header(HttpHeaders.Authorization)
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substring("Bearer ".length)
        if (presented == null || !MessageDigest.isEqual(sha256(presented), expected)) throw DomainError.Unauthenticated()
        body()
    }
}

private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

/** What a run of the retention job answers Vercel Cron: rows changed per task. Not part of the contract. */
@Serializable
data class RetentionResult(
    val tasks: Map<String, Int>,
)

/** The daily retention job (core-and-auth §10): runs every feature's [RetentionTask], one after another. */
class RetentionJobRoutes(
    private val tasks: List<RetentionTask>,
) {
    fun install(route: Route) {
        route.job(Endpoints.Jobs.RETENTION) {
            call.respond(RetentionResult(tasks.associate { it.name to it.run() }))
        }
    }
}
