package io.github.phongmanh.restaurant.support

import io.github.phongmanh.restaurant.core.application.RateLimiter
import io.github.phongmanh.restaurant.core.http.Endpoint
import io.github.phongmanh.restaurant.core.http.caller
import io.github.phongmanh.restaurant.core.http.endpoint
import io.ktor.http.HttpMethod
import io.ktor.server.response.respondText
import io.ktor.server.testing.ApplicationTestBuilder
import org.koin.core.module.Module
import org.koin.dsl.module
import java.time.Clock

/** Made-up registry entries, one per authenticated group, that answer with the caller they got. */
object Probe {
    val STAFF = Endpoint(HttpMethod.Get, "/v1/staff/test-whoami", idempotencyKey = false, ifMatch = false, allowedWhenSuspended = false)
    val STAFF_WHEN_SUSPENDED = Endpoint(HttpMethod.Get, "/v1/staff/test-whoami-anyway", false, false, allowedWhenSuspended = true)
    val ADMIN = Endpoint(HttpMethod.Get, "/v1/admin/test-whoami", idempotencyKey = false, ifMatch = false, allowedWhenSuspended = true)
    val GUEST = Endpoint(HttpMethod.Get, "/v1/guest/test-whoami", idempotencyKey = false, ifMatch = false, allowedWhenSuspended = false)
}

/** A store that never limits, so tests that sign in often don't trip the per-IP limits. */
val NoLimits = RateLimiter { _, _ -> 1L }

/**
 * The service with [clock] as its clock, [limiter] as its rate-limit store (`null`: the real
 * in-memory one), plus the [Probe] routes.
 */
fun authApp(
    clock: MutableClock = MutableClock(),
    limiter: RateLimiter? = NoLimits,
    overrides: List<Module> = emptyList(),
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApp(
    overrides =
        listOf(
            module {
                single<Clock> { clock }
                if (limiter != null) single<RateLimiter> { limiter }
            },
        ) + overrides,
    routes = {
        listOf(Probe.STAFF, Probe.STAFF_WHEN_SUSPENDED, Probe.ADMIN, Probe.GUEST).forEach { probe ->
            endpoint(probe) { call.respondText(call.caller().toString()) }
        }
    },
    block = block,
)
