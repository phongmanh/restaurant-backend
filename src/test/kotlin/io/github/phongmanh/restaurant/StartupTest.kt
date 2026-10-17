package io.github.phongmanh.restaurant

import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * The service starts the way the image starts it: `EngineMain` loads the module `application.yaml`
 * names, with no settings passed in. Every other test passes an `AppConfig` to [service] itself, so
 * none of them would notice if that name stopped resolving (found deploying staging, Phase 5 step 2).
 */
class StartupTest {
    @Test
    fun `the module application yaml names reads the settings itself`() {
        val failure =
            assertFails {
                testApplication {
                    environment { config = ApplicationConfig("application.yaml") }
                    startApplication()
                }
            }
        // The test JVM has no secrets, so the startup check refuses; reaching it shows the module resolved.
        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { it.startsWith("missing settings: ") }, messages.joinToString(" | "))
    }
}
