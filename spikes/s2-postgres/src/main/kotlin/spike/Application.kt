package spike

import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Starts the spike server on `PORT` (default 8080). Both pools open before the server starts, so a
 * cold start includes connecting to the database (Q7). Reads `DATABASE_URL_APP_POOLED` and
 * `DATABASE_URL_APP_DIRECT`.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val stats = InstanceStats()
    val db = Database(
        pooled = JdbcTarget.parse(requiredEnv("DATABASE_URL_APP_POOLED")),
        direct = JdbcTarget.parse(requiredEnv("DATABASE_URL_APP_DIRECT")),
    )
    val server = embeddedServer(Netty, port = port, host = "0.0.0.0") { spikeModule(stats, db) }
    server.monitor.subscribe(ApplicationStopped) { db.close() }
    server.start(wait = true)
}

private fun requiredEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("$name is not set")

private const val DEFAULT_PORT = 8080
