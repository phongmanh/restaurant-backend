package spike

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/** Starts the probe server on `PORT` (default 8080; Vercel needs `PORT=8080` set on the project). */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    embeddedServer(Netty, port = port, host = "0.0.0.0") { probeModule() }.start(wait = true)
}

private const val DEFAULT_PORT = 8080
