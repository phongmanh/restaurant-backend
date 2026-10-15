package spike

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * The spike on http://localhost:8080 against the in-process PostgreSQL of the tests, to dry-run
 * scripts/measure.mjs: `./gradlew runLocal`. No pooler, so it proves the script, not the numbers.
 */
fun main() {
    val db = TestDatabase.db
    embeddedServer(Netty, port = 8080, host = "127.0.0.1") { spikeModule(InstanceStats(), db) }.start(wait = true)
}
