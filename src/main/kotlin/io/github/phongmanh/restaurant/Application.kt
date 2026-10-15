package io.github.phongmanh.restaurant

import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.installHttp
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

fun main(args: Array<String>) = EngineMain.main(args)

@Serializable
data class Health(
    val status: String,
)

/** The service. `application.yaml` names this module; tests start it with `testApplication`. */
fun Application.module() {
    installHttp()
    routing {
        // Answers without touching the database (core-and-auth §2).
        get(Endpoints.HEALTH) { call.respond(Health("ok")) }
    }
}
