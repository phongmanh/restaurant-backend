package spike

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ServerReady
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.UUID

private val log = LoggerFactory.getLogger("spike")

/** The two seeded tenants (V1__spike.sql). */
val RESTAURANT_A: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a")
val RESTAURANT_B: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000b")

private const val MAX_HOLD_MS = 10_000L

/**
 * The spike's endpoints. Each one answers one S-2 question; see the README.
 *
 * @param env reads environment variables; replaced in tests.
 */
fun Application.spikeModule(
    stats: InstanceStats,
    db: Database,
    env: (String) -> String? = System::getenv,
) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        // A database error is a result here (Q3 counts them), so answer it as data, not a bare 500.
        exception<SQLException> { call, e ->
            log.warn("sql error {} {}", e.sqlState, e.message)
            call.respond(HttpStatusCode.InternalServerError, SqlError(stats.instanceId, e.sqlState, e.message.orEmpty()))
        }
    }

    monitor.subscribe(ServerReady) {
        stats.markReady()
        log.info("spike ready {} pools={}", stats.startupSummary(), db.poolStats())
    }

    intercept(ApplicationCallPipeline.Monitoring) {
        stats.requestStarted()
        try {
            proceed()
        } finally {
            stats.requestFinished()
        }
    }

    val queries = DbQueries(db)

    routing {
        get("/health/live") { call.respondText("ok") }

        // Cold start (startupMs includes opening both pools) and the pools on this instance.
        get("/spike/info") {
            val s = stats.snapshot()
            call.respond(
                Info(
                    instanceId = stats.instanceId,
                    firstRequestOnInstance = stats.claimFirstRequest(),
                    startupMs = s.startupMs,
                    uptimeMs = s.uptimeMs,
                    requestsServed = s.requestsServed,
                    region = env("VERCEL_REGION"),
                    pools = db.poolStats(),
                ),
            )
        }

        // Q1: one SELECT 1.
        get("/spike/db/ping") {
            val via = call.via() ?: return@get
            call.respond(io { queries.ping(via) }.copy(instanceId = stats.instanceId))
        }

        // Q2 and Q3: the order-placement transaction.
        post("/spike/db/order") {
            val via = call.via() ?: return@post
            val restaurant = call.restaurant() ?: return@post
            val threshold = call.boundedLong("prepareThreshold", 0L..100L, Database.DEFAULT_PREPARE_THRESHOLD.toLong()) ?: return@post
            val key = call.request.queryParameters["key"] ?: UUID.randomUUID().toString()
            call.respond(io { queries.placeOrder(via, restaurant, key, threshold.toInt()) }.copy(instanceId = stats.instanceId))
        }

        // Q4: the fixed RLS checks.
        get("/spike/db/rls") {
            val via = call.via() ?: return@get
            call.respond(io { queries.rlsChecks(via) })
        }

        // Q4: one request of the mixed-tenant burst.
        get("/spike/db/tenant") {
            val via = call.via() ?: return@get
            val restaurant = call.restaurant() ?: return@get
            call.respond(io { queries.tenantProbe(via, restaurant) }.copy(instanceId = stats.instanceId))
        }

        // Q5: a transaction advisory lock, held for holdMs.
        post("/spike/db/lock") {
            val via = call.via() ?: return@post
            val key = call.boundedLong("key", Long.MIN_VALUE..Long.MAX_VALUE) ?: return@post
            val holdMs = call.boundedLong("holdMs", 0L..MAX_HOLD_MS, default = 2_000) ?: return@post
            call.respond(io { queries.advisoryLock(via, key, holdMs) }.copy(instanceId = stats.instanceId))
        }

        // Q7: server connections by role and state, seen over the direct connection.
        get("/spike/db/connections") {
            call.respond(io { queries.connections() }.copy(instanceId = stats.instanceId, pools = db.poolStats()))
        }
    }
}

/** JDBC blocks its thread, so every database call runs on the IO dispatcher. */
private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private suspend fun ApplicationCall.via(): Via? =
    when (request.queryParameters["via"] ?: "pooled") {
        "pooled" -> Via.POOLED
        "direct" -> Via.DIRECT
        else -> null.also { respondText("'via' must be pooled or direct", status = HttpStatusCode.BadRequest) }
    }

private suspend fun ApplicationCall.restaurant(): UUID? =
    when (request.queryParameters["restaurant"] ?: "a") {
        "a" -> RESTAURANT_A
        "b" -> RESTAURANT_B
        else -> null.also { respondText("'restaurant' must be a or b", status = HttpStatusCode.BadRequest) }
    }

/**
 * Reads a whole-number query parameter within [range]. On a missing or bad value it answers 400
 * and returns null, so the caller just returns.
 */
private suspend fun ApplicationCall.boundedLong(name: String, range: LongRange, default: Long? = null): Long? {
    val raw = request.queryParameters[name]
    val value = raw?.toLongOrNull() ?: default.takeIf { raw == null }
    if (value == null || value !in range) {
        respondText("'$name' must be a whole number in $range", status = HttpStatusCode.BadRequest)
        return null
    }
    return value
}

@Serializable
data class Info(
    val instanceId: String,
    val firstRequestOnInstance: Boolean,
    val startupMs: Long?,
    val uptimeMs: Long,
    val requestsServed: Long,
    val region: String?,
    val pools: Map<String, PoolStats>,
)

@Serializable
data class SqlError(val instanceId: String, val sqlState: String?, val message: String)
