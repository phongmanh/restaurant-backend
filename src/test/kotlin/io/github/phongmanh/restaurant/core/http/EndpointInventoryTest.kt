package io.github.phongmanh.restaurant.core.http

import io.github.phongmanh.restaurant.Contract
import io.github.phongmanh.restaurant.service
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.notYetRouted
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * IT-CORE-10 (core-and-auth §15): the routes Ktor registers, the registry in `Endpoints.kt` and the
 * contract's operations are the same set (method + path), and the registry's flags match the
 * contract. `/health` and `/internal/jobs/` are left out, as they aren't in the contract.
 */
class EndpointInventoryTest {
    private fun Endpoint.key() = "${method.value} $path"

    private fun Contract.Operation.key() = "$method $path"

    @Test
    fun `registry declares exactly the contract's operations`() {
        val registry = Endpoints.all.map { it.key() }
        assertEquals(registry.size, registry.toSet().size, "an operation is declared twice")
        assertEquals(Contract.operations.map { it.key() }.sorted(), registry.sorted())
    }

    @Test
    fun `registry flags match the contract`() {
        val byKey = Endpoints.all.associateBy { it.key() }
        val mismatches =
            Contract.operations.mapNotNull { op ->
                val entry = byKey.getValue(op.key())
                val expected = Triple(op.idempotencyKey, op.ifMatch, op.allowedWhenSuspended)
                val actual = Triple(entry.idempotencyKey, entry.ifMatch, entry.allowedWhenSuspended)
                if (expected == actual) null else "${op.key()} (${op.operationId}): contract $expected, registry $actual"
            }
        assertTrue(mismatches.isEmpty(), "(Idempotency-Key, If-Match, x-allowed-when-suspended) differ:\n" + mismatches.joinToString("\n"))
    }

    @Test
    fun `the QR credential flag matches x-auth qr`() {
        val qr = Contract.operations.filter { it.auth == "qr" }.map { it.key() }
        assertEquals(
            qr.sorted(),
            Endpoints.all
                .filter { it.qrCredential }
                .map { it.key() }
                .sorted(),
        )
    }

    @Test
    fun `ktor routes equal the registry minus the entries not built yet`() =
        testApplication {
            lateinit var app: Application
            application {
                service(TestDatabase.config)
                app = this
            }
            startApplication()

            val routed =
                app
                    .plugin(RoutingRoot)
                    .getAllRoutes()
                    .mapNotNull { it.methodAndPath() }
                    .filterNot { (_, path) -> path == Endpoints.HEALTH || path.startsWith("/internal/jobs/") }
                    .map { (method, path) -> "$method $path" }
            assertEquals(routed.size, routed.toSet().size, "a route is registered twice")

            val expected = (Endpoints.all - notYetRouted).map { it.key() }
            assertEquals(expected.sorted(), routed.sorted())
        }

    /** The method and the path, with `{name}` placeholders, of a route that has a handler. */
    private fun RoutingNode.methodAndPath(): Pair<String, String>? {
        val method = (selector as? HttpMethodRouteSelector)?.method?.value ?: return null
        val segments =
            generateSequence(this) { it.parent }
                .mapNotNull { node ->
                    when (val s = node.selector) {
                        is PathSegmentConstantRouteSelector -> s.value
                        is PathSegmentParameterRouteSelector -> "{${s.name}}"
                        else -> null
                    }
                }.toList()
                .reversed()
        return method to segments.joinToString("/", prefix = "/")
    }
}
