package io.github.phongmanh.restaurant.support

import io.github.phongmanh.restaurant.core.config.AppConfig
import io.github.phongmanh.restaurant.module
import io.ktor.server.application.Application
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

/**
 * The whole service on the test database, plus [routes] that exist only in the test (under
 * `/test/`, or a made-up registry entry). [block] sends the requests.
 */
fun testApp(
    config: AppConfig = TestDatabase.config,
    routes: Routing.(Application) -> Unit = {},
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application {
        module(config)
        routing { routes(this@application) }
    }
    block()
}
