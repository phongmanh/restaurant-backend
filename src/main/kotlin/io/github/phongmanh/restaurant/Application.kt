package io.github.phongmanh.restaurant

import io.github.phongmanh.restaurant.core.config.AppConfig
import io.github.phongmanh.restaurant.core.coreModule
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.installHttp
import io.github.phongmanh.restaurant.core.persistence.DatabasePools
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.koin.core.module.Module
import org.koin.ktor.plugin.KoinIsolated

fun main(args: Array<String>) = EngineMain.main(args)

@Serializable
data class Health(
    val status: String,
)

/** The service, as `application.yaml` names it: reads the settings, then starts. */
fun Application.module() = module(AppConfig.load(environment.config))

/** The service with the given settings; tests start it this way with `testApplication`. */
fun Application.module(config: AppConfig) {
    installHttp(config.http)
    // Opened here, at startup, so a cold start pays the connect time before the first request.
    val pools = DatabasePools(config.database)
    monitor.subscribe(ApplicationStopped) { pools.close() }
    // Isolated: each application (and each test) gets its own graph, not a global one.
    install(KoinIsolated) { modules(appModule(pools)) }
    routing {
        // Answers without touching the database (core-and-auth §2).
        get(Endpoints.HEALTH) { call.respond(Health("ok")) }
    }
}

/** The dependency graph (architecture §11): the core, then one module per feature as they're built. */
fun appModule(pools: DatabasePools): List<Module> = listOf(coreModule(pools))
