package io.github.phongmanh.restaurant.support

import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Records every (method, path, status) the tests produce, for the route-test matrix the RTM checks
 * at G4 (401 / 400 / 404 / success per endpoint). At the end of the test JVM it writes
 * `build/route-log/<pid>.tsv`; `scripts/route-matrix.py` maps the paths onto the registry.
 * Paths only: no bodies, tokens or query strings.
 */
object RouteLog {
    private val seen = ConcurrentHashMap.newKeySet<String>()

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                val dir = File("build/route-log").apply { mkdirs() }
                File(dir, "${ProcessHandle.current().pid()}.tsv").writeText(seen.sorted().joinToString("\n", postfix = "\n"))
            },
        )
    }

    val plugin =
        createApplicationPlugin("RouteLog") {
            on(ResponseSent) { call ->
                val status = call.response.status()?.value ?: return@on
                seen += "${call.request.httpMethod.value}\t${call.request.path()}\t$status"
            }
        }
}
