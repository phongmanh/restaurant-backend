package io.github.phongmanh.restaurant.core.observability

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.File
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The alert rules as code (architecture §13, NFR-OBS-01; Phase 5 step 4c), checked offline: the
 * half of ST-07 that needs no backend. promtool, in the Prometheus image, lints `rules.yaml` and runs
 * `rules-test.yaml`, whose synthetic series inject error rates and delays and say which alerts fire
 * when. MetricNamesTest checks that the series they read are the ones the service pushes.
 */
class AlertRulesTest {
    @Test
    fun `promtool passes the rules and their unit tests`() {
        GenericContainer(DockerImageName.parse(PROMETHEUS_IMAGE))
            .withCopyFileToContainer(MountableFile.forHostPath(AlertRules.dir.absolutePath), "/rules")
            .withCreateContainerCmdModifier { it.withEntrypoint("sh") }
            // Always exits 0, so the output survives a failure; the assertion reads the exit code.
            .withCommand("-c", "cd /rules && promtool check rules rules.yaml && promtool test rules rules-test.yaml; echo EXIT=\$?")
            .withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))
            .use { container ->
                container.start()
                val output = container.logs
                assertTrue("EXIT=0" in output, output)
            }
    }

    @Test
    fun `every alert has a severity, a summary and a runbook section that exists`() {
        val runbooks = File(AlertRules.dir, "runbooks.md").readLines()
        // GitHub's anchor for a heading: lower case, punctuation dropped, spaces as hyphens.
        val anchors =
            runbooks
                .filter { it.startsWith("## ") }
                .map {
                    it
                        .removePrefix("## ")
                        .lowercase()
                        .replace(Regex("[^a-z0-9 -]"), "")
                        .replace(' ', '-')
                }.toSet()
        val base = "https://github.com/phongmanh/restaurant-backend/blob/main/deploy/observability/runbooks.md#"
        AlertRules.alerts.forEach { alert ->
            val name = alert["alert"]

            @Suppress("UNCHECKED_CAST")
            val labels = alert["labels"] as Map<String, String>

            @Suppress("UNCHECKED_CAST")
            val annotations = alert["annotations"] as Map<String, String>
            assertTrue(labels["severity"] in setOf("critical", "warning"), "$name: severity")
            assertTrue(!annotations["summary"].isNullOrBlank(), "$name: summary")
            val url = annotations["runbook_url"].orEmpty()
            assertTrue(url.startsWith(base) && url.removePrefix(base) in anchors, "$name: runbook $url, sections $anchors")
        }
    }

    @Test
    fun `every SLO of architecture 13 has a fast and a slow burn-rate alert`() {
        val slos =
            AlertRules.alerts
                .filter { (it["alert"] as String).contains("Burn") }
                .groupBy({ (it["labels"] as Map<*, *>)["slo"] }, { (it["alert"] as String).substringAfter("Burn") })
        assertEquals(setOf("availability", "order_flow", "guest_read_p95", "guest_read_p99"), slos.keys)
        slos.forEach { (slo, speeds) -> assertEquals(setOf("Fast", "Slow"), speeds.toSet(), "$slo") }
    }

    private companion object {
        /** Prometheus 3.5, a long-term-support release. */
        const val PROMETHEUS_IMAGE = "prom/prometheus:v3.5.0"
    }
}
