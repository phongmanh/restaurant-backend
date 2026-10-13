package spike

import io.ktor.server.engine.embeddedServer
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.netty.Netty
import kotlinx.coroutines.asCoroutineDispatcher
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Executors

private val log = LoggerFactory.getLogger("spike")

/**
 * Starts the spike server on `PORT` (default 8080).
 *
 * With `-Dspike.training=true` it instead does a short training run for the JDK AOT cache
 * (`-XX:AOTCacheOutput`): it starts, calls each endpoint once so their classes get loaded, and
 * stops, so the JVM writes the cache on exit. See `Dockerfile.vercel-aot`.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val stats = InstanceStats()
    // A pool of its own, one thread per processor the container grants, so /spike/cpu measures
    // the instance's CPU share without competing with Ktor's threads.
    val cpuDispatcher = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        .asCoroutineDispatcher()
    val server = embeddedServer(Netty, port = port, host = "0.0.0.0") { spikeModule(stats, cpuDispatcher) }
    server.monitor.subscribe(ApplicationStopped) { cpuDispatcher.close() }

    if (System.getProperty("spike.training").toBoolean()) {
        server.start(wait = false)
        train(port)
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        return
    }

    // Vercel sends SIGTERM when it scales an idle instance down; this line shows when and after
    // how much work. Logback installs no shutdown hook of its own, so it still logs here.
    Runtime.getRuntime().addShutdownHook(Thread { log.info("spike shutdown {}", stats.shutdownSummary()) })
    server.start(wait = true)
}

private fun train(port: Int) {
    val client = HttpClient.newHttpClient()
    listOf(
        "/health/live",
        "/spike/info",
        "/spike/sleep?ms=10",
        "/spike/cpu?ms=10",
        "/spike/sse?seconds=1&intervalMs=200",
    ).forEach { path ->
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).build()
        val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        log.info("training {} -> {}", path, status)
    }
}

private const val DEFAULT_PORT = 8080
