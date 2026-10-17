package io.github.phongmanh.restaurant

import io.github.phongmanh.restaurant.core.application.RateLimiter
import io.github.phongmanh.restaurant.core.auth.authModule
import io.github.phongmanh.restaurant.core.auth.http.AuthRoutes
import io.github.phongmanh.restaurant.core.config.AppConfig
import io.github.phongmanh.restaurant.core.coreModule
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.RetentionJobRoutes
import io.github.phongmanh.restaurant.core.http.installHttp
import io.github.phongmanh.restaurant.core.observability.Metrics
import io.github.phongmanh.restaurant.core.observability.installMetrics
import io.github.phongmanh.restaurant.core.persistence.DatabasePools
import io.github.phongmanh.restaurant.core.persistence.UpstashRateLimiter
import io.github.phongmanh.restaurant.menu.http.MenuEditRoutes
import io.github.phongmanh.restaurant.menu.http.MenuReadRoutes
import io.github.phongmanh.restaurant.menu.menuModule
import io.github.phongmanh.restaurant.orders.http.GuestOrderRoutes
import io.github.phongmanh.restaurant.orders.http.StaffBillRoutes
import io.github.phongmanh.restaurant.orders.http.StaffOrderRoutes
import io.github.phongmanh.restaurant.orders.ordersModule
import io.github.phongmanh.restaurant.realtime.http.RealtimeJobRoutes
import io.github.phongmanh.restaurant.realtime.http.RealtimeRoutes
import io.github.phongmanh.restaurant.realtime.realtimeModule
import io.github.phongmanh.restaurant.reports.http.ReportJobRoutes
import io.github.phongmanh.restaurant.reports.http.StaffReportRoutes
import io.github.phongmanh.restaurant.reports.reportsModule
import io.github.phongmanh.restaurant.restaurants.http.AdminRoutes
import io.github.phongmanh.restaurant.restaurants.http.AuditAndPrivacyRoutes
import io.github.phongmanh.restaurant.restaurants.http.StaffMemberRoutes
import io.github.phongmanh.restaurant.restaurants.http.StaffRestaurantRoutes
import io.github.phongmanh.restaurant.restaurants.restaurantsModule
import io.github.phongmanh.restaurant.tables.http.GuestSessionRoutes
import io.github.phongmanh.restaurant.tables.http.StaffSessionRoutes
import io.github.phongmanh.restaurant.tables.http.StaffTableRoutes
import io.github.phongmanh.restaurant.tables.http.TableJobRoutes
import io.github.phongmanh.restaurant.tables.tablesModule
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.netty.EngineMain
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.KoinIsolated

fun main(args: Array<String>) = EngineMain.main(args)

@Serializable
data class Health(
    val status: String,
)

/**
 * The service, as `application.yaml` names it: reads the settings, then starts. Keep it the only
 * function named `module`: given two, Ktor 3 picked the one with parameters and tried to inject
 * them, so the image didn't start (`StartupTest`; Phase 5 step 2).
 */
fun Application.module() = service(AppConfig.load(environment.config))

/**
 * The service with the given settings; tests start it this way with `testApplication`, and may
 * replace components (a clock they move, a failing rate-limit store) with [overrides].
 */
fun Application.service(
    config: AppConfig,
    overrides: List<Module> = emptyList(),
) {
    // First, so the pools and the HTTP timer report from the first request (ADR-0009).
    val metrics = Metrics.registry(config.observability, config.environment)
    installMetrics(metrics, config.http.minClientVersions.keys)
    installHttp(config.http)
    // Opened here, at startup, so a cold start pays the connect time before the first request.
    val pools = DatabasePools(config.database, metrics)
    monitor.subscribe(ApplicationStopped) {
        pools.close()
        // Last: closing an OTLP registry pushes what it hasn't sent yet, before the instance goes.
        metrics.close()
    }
    // Isolated: each application (and each test) gets its own graph, not a global one.
    install(KoinIsolated) { modules(appModule(config, pools, metrics) + overrides) }
    // One per instance start: the cold starts of architecture §13.
    metrics.counter("app.starts").increment()
    // The rate-limit store's connection opens now, not on the first sign-in (S-1 Q10 design rule).
    (get<RateLimiter>() as? UpstashRateLimiter)?.let { upstash ->
        launch { runCatching { upstash.warmUp() }.onFailure { log.warn("rate limit store didn't answer at startup") } }
    }
    routing {
        // Answers without touching the database (core-and-auth §2).
        get(Endpoints.HEALTH) { call.respond(Health("ok")) }
        get<AuthRoutes>().install(this)
        get<StaffRestaurantRoutes>().install(this)
        get<StaffMemberRoutes>().install(this)
        get<AdminRoutes>().install(this)
        get<AuditAndPrivacyRoutes>().install(this)
        get<MenuReadRoutes>().install(this)
        get<MenuEditRoutes>().install(this)
        get<GuestSessionRoutes>().install(this)
        get<StaffTableRoutes>().install(this)
        get<StaffSessionRoutes>().install(this)
        get<TableJobRoutes>().install(this)
        get<GuestOrderRoutes>().install(this)
        get<StaffOrderRoutes>().install(this)
        get<StaffBillRoutes>().install(this)
        get<RetentionJobRoutes>().install(this)
        get<RealtimeRoutes>().install(this)
        get<RealtimeJobRoutes>().install(this)
        get<StaffReportRoutes>().install(this)
        get<ReportJobRoutes>().install(this)
    }
}

/** The dependency graph (architecture §11): the core, then one module per feature as they're built. */
fun appModule(
    config: AppConfig,
    pools: DatabasePools,
    metrics: MeterRegistry = SimpleMeterRegistry(),
): List<Module> =
    listOf(
        module { single<MeterRegistry> { metrics } },
        coreModule(pools, config.jobs),
        authModule(config.auth, config.rateLimits),
        restaurantsModule(),
        menuModule(),
        tablesModule(config.guests, config.auth.issuer),
        ordersModule(),
        realtimeModule(config.realtime),
        reportsModule(),
    )
