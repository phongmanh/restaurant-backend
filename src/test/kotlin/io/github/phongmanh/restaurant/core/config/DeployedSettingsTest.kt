package io.github.phongmanh.restaurant.core.config

import io.github.phongmanh.restaurant.support.TestKeys
import io.ktor.server.config.MapApplicationConfig
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Each environment's committed settings pass the startup checks (core-and-auth §13). No deployment
 * had started with `APP_ENV=production` before release 1, and its extra checks would otherwise first
 * run on the production deploy itself (release-checklist P-1). The settings are resolved from
 * `application.yaml` the way Ktor resolves them, with test values for the secrets.
 */
class DeployedSettingsTest {
    @Test
    fun `staging's and production's settings pass the startup checks`() {
        for (name in listOf("staging", "production")) {
            val c = AppConfig.load(resolve(envFile(name) + secrets))
            assertEquals(name, c.environment)
            assertEquals(LivePush.OFF, c.realtime.livePush, "$name: release 1 runs without live push (CR-013)")
        }
    }

    @Test
    fun `production's own checks run on its settings`() {
        val e =
            assertFailsWith<IllegalStateException> {
                AppConfig.load(resolve(envFile("production") + secrets + ("STAFF_APP_URL" to "http://staff.production.example")))
            }
        assertTrue("auth.staffAppUrl" in e.message!!, e.message)
    }

    @Test
    fun `every setting in an environment file is one application yaml reads`() {
        val known = variable.findAll(yaml.readText()).map { it.groupValues[1] }.toSet()
        for (name in listOf("staging", "production")) {
            val unknown = envFile(name).keys - known
            assertTrue(unknown.isEmpty(), "deploy/$name/$name.env sets settings nothing reads: $unknown")
        }
    }

    private val yaml = File("src/main/resources/application.yaml")
    private val entry = Regex("""^( *)([\w-]+):\s*(.*)$""")
    private val variable = Regex("""\$(\w+):""")

    /** `deploy/<name>/<name>.env`, read as `push-env.sh` reads it: `NAME=value` lines, `#` comments. */
    private fun envFile(name: String): Map<String, String> =
        File("deploy/$name/$name.env")
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    /** `application.yaml`'s settings outside `ktor`, each `"$NAME:default"` taken from [environment] or its default. */
    private fun resolve(environment: Map<String, String>): MapApplicationConfig {
        val parents = ArrayDeque<Pair<Int, String>>()
        val values = mutableListOf<Pair<String, String>>()
        for ((indent, key, raw) in yaml.readLines().mapNotNull { entry.find(it)?.destructured }) {
            while (parents.isNotEmpty() && parents.last().first >= indent.length) parents.removeLast()
            val value = raw.trim().removeSurrounding("\"")
            val path = (parents.map { it.second } + key).joinToString(".")
            if (value.isEmpty()) {
                parents.addLast(indent.length to key)
            } else if (!path.startsWith("ktor.")) {
                val reference = Regex("""^\$(\w+):(.*)$""").find(value)?.destructured
                values += path to (reference?.let { (name, default) -> environment[name] ?: default } ?: value)
            }
        }
        return MapApplicationConfig(*values.toTypedArray())
    }

    /** What `.env.<environment>` holds, as test values. */
    private val secrets =
        mapOf(
            "DATABASE_URL" to "postgresql://app:pw@db.example.test/restaurant?sslmode=verify-full",
            "DATABASE_URL_PLATFORM" to "postgresql://app_platform:pw@db.example.test/restaurant?sslmode=verify-full",
            "STAFF_JWT_KEYS" to TestKeys.staffKeysJson,
            "GUEST_JWT_KEYS" to TestKeys.guestKeysJson,
            "QR_SIGNING_KEYS" to TestKeys.qrKeysJson,
            "TOTP_ENCRYPTION_KEYS" to TestKeys.totpKeysJson,
            "EMAIL_HMAC_KEY" to Base64.getEncoder().encodeToString(ByteArray(32)),
            "CRON_SECRET" to TestKeys.CRON_SECRET,
            "UPSTASH_REDIS_REST_URL" to "https://x.upstash.io",
            "UPSTASH_REDIS_REST_TOKEN" to "t",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to "https://otlp.example.test/otlp",
            "OTEL_EXPORTER_OTLP_HEADERS" to "Authorization=Basic b3RscC1zZWNyZXQ=",
        )
}
