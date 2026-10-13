package spike

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpikeModuleTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.spike() {
        application { spikeModule(InstanceStats(), TestDatabase.db, env = mapOf("VERCEL_REGION" to "sin1")::get) }
    }

    @Test
    fun `migrate - app is denied every DDL probe`() {
        val report = TestDatabase.migration

        assertTrue(report.ok, report.lines.joinToString("\n"))
        assertEquals(3, report.lines.count { "denied (42501)" in it })
    }

    @Test
    fun `migrate - run again - applies nothing and still passes`() {
        TestDatabase.migration

        val again = migrate(TestDatabase.migrator, TestDatabase.app)

        assertTrue(again.ok)
        assertTrue(again.lines.any { "0 migration(s) applied" in it }, again.lines.joinToString("\n"))
    }

    @Test
    fun `info - reports both pools`() = testApplication {
        spike()

        val info = json.decodeFromString<Info>(client.get("/spike/info").bodyAsText())

        assertEquals(setOf("pooled", "direct"), info.pools.keys)
        assertEquals("sin1", info.region)
    }

    @Test
    fun `ping - answers with timings`() = testApplication {
        spike()

        val ping = json.decodeFromString<PingResult>(client.get("/spike/db/ping?via=direct").bodyAsText())

        assertEquals("direct", ping.via)
        assertTrue(ping.queryMs > 0)
    }

    @Test
    fun `order - same key twice - second is a replay of the first`() = testApplication {
        spike()
        val key = UUID.randomUUID().toString()

        val first = json.decodeFromString<OrderResult>(client.post("/spike/db/order?restaurant=a&key=$key").bodyAsText())
        val second = json.decodeFromString<OrderResult>(client.post("/spike/db/order?restaurant=a&key=$key").bodyAsText())

        assertFalse(first.replayed)
        assertTrue(second.replayed)
        assertEquals(first.orderId, second.orderId)
    }

    @Test
    fun `order - prepareThreshold 1, repeated - server-side prepared statements work without a pooler`() = testApplication {
        spike()

        repeat(5) {
            val response = client.post("/spike/db/order?restaurant=b&prepareThreshold=1")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        }
    }

    @Test
    fun `order - bad parameters - 400`() = testApplication {
        spike()

        listOf("via=other", "restaurant=c", "prepareThreshold=-1").forEach {
            assertEquals(HttpStatusCode.BadRequest, client.post("/spike/db/order?$it").status, it)
        }
    }

    @Test
    fun `rls - every check passes`() = testApplication {
        spike()

        val result = json.decodeFromString<RlsResult>(client.get("/spike/db/rls").bodyAsText())

        assertTrue(result.allPass, result.checks.filterNot { it.pass }.joinToString("\n"))
        assertEquals(10, result.checks.size)
    }

    @Test
    fun `tenant - mixed burst - each request sees only its own tenant and nothing leaks after commit`() = testApplication {
        spike()

        val probes = coroutineScope {
            (0 until 40).map { i ->
                async { json.decodeFromString<TenantProbe>(client.get("/spike/db/tenant?restaurant=${if (i % 2 == 0) "a" else "b"}").bodyAsText()) }
            }.awaitAll()
        }

        probes.forEach { p ->
            assertEquals(p.requested, p.setting)
            assertEquals(listOf(p.requested), p.visibleRestaurants)
            assertTrue(p.settingAfterCommit.isNullOrEmpty(), "leaked: ${p.settingAfterCommit}")
            assertEquals(emptyList(), p.visibleAfterCommit)
        }
    }

    @Test
    fun `lock - three concurrent on one key - exactly one holds it, released at commit`() = testApplication {
        spike()
        val key = System.nanoTime()

        // Three, the pool's size, so none waits for a connection until the holder has committed.
        val results = coroutineScope {
            (0 until 3).map {
                async { json.decodeFromString<LockResult>(client.post("/spike/db/lock?key=$key&holdMs=500").bodyAsText()) }
            }.awaitAll()
        }

        assertEquals(1, results.count { it.acquired }, results.toString())
        val holder = results.single { it.acquired }
        assertTrue(holder.heldUntilMs - holder.heldFromMs >= 500)
        assertTrue(holder.reacquiredAfterCommit)
    }

    @Test
    fun `connections - lists app connections`() = testApplication {
        spike()

        val result = json.decodeFromString<ConnectionsResult>(client.get("/spike/db/connections").bodyAsText())

        assertTrue(result.maxConnections > 0)
        assertTrue(result.byRoleAndState.any { it.role == "app" }, result.toString())
    }
}
