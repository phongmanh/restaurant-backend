package io.github.phongmanh.restaurant.support

import io.github.phongmanh.restaurant.core.config.AppConfig
import io.github.phongmanh.restaurant.module
import io.github.phongmanh.restaurant.realtime.application.RealtimePublisher
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The whole service on the test database, plus [routes] that exist only in the test (under
 * `/test/`, or a made-up registry entry), with components replaced by [overrides] (a clock the test
 * moves). [block] sends the requests. Ably is always a [FakeAbly], so no test reaches the real
 * service; a test that inspects it passes its own in [overrides]. Every response is checked against
 * the contract ([ContractCheck]): the test fails when one breaks it.
 */
fun testApp(
    config: AppConfig = TestDatabase.config,
    overrides: List<Module> = emptyList(),
    routes: Routing.(Application) -> Unit = {},
    block: suspend ApplicationTestBuilder.() -> Unit,
) {
    val contract = ContractCheck()
    testApplication {
        application {
            install(RouteLog.plugin)
            install(contract.plugin)
            module(config, listOf(module { single<RealtimePublisher> { FakeAbly() } }) + overrides)
            routing { routes(this@application) }
        }
        block()
    }
    contract.assertNone()
}
