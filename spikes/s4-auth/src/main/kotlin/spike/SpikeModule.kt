package spike

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ServerReady
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("spike")

/**
 * The spike's endpoints: the sign-in routes as architecture §7 describes them, two protected
 * routes (staff and admin), and spike-only measurement routes. See the README.
 *
 * `?lib=bc|p4j` on sign-in picks the Argon2id library (Q2); the service would have one.
 */
fun Application.spikeModule(
    stats: InstanceStats,
    auth: AuthService,
    tokens: Tokens,
    env: (String) -> String? = System::getenv,
) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request")) }
        exception<ContentTransformationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request")) }
    }
    install(Authentication) {
        jwt(Audience.STAFF) {
            verifier(tokens.verifier(Audience.STAFF))
            validate { JWTPrincipal(it.payload) }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorBody("invalid_token")) }
        }
        jwt(Audience.ADMIN) {
            verifier(tokens.verifier(Audience.ADMIN))
            validate { JWTPrincipal(it.payload) }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorBody("invalid_token")) }
        }
    }

    monitor.subscribe(ServerReady) {
        stats.markReady()
        log.info("spike ready {}", stats.startupSummary())
    }

    intercept(ApplicationCallPipeline.Monitoring) {
        stats.requestStarted()
        try {
            proceed()
        } finally {
            stats.requestFinished()
        }
    }

    routing {
        get("/health/live") { call.respondText("ok") }

        get("/spike/info") {
            val s = stats.snapshot()
            val runtime = Runtime.getRuntime()
            call.respond(
                Info(
                    instanceId = stats.instanceId,
                    firstRequestOnInstance = stats.claimFirstRequest(),
                    startupMs = s.startupMs,
                    uptimeMs = s.uptimeMs,
                    requestsServed = s.requestsServed,
                    region = env("VERCEL_REGION"),
                    cpus = runtime.availableProcessors(),
                    maxHeapMb = runtime.maxMemory() / MB,
                    heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB,
                    memory = processMemory(),
                    javaVersion = System.getProperty("java.version"),
                ),
            )
        }

        post("/v1/auth/sign-in") {
            val startedAt = System.nanoTime()
            val firstOnInstance = stats.claimFirstRequest()
            val lib = call.request.queryParameters["lib"] ?: "bc"
            if (lib !in HASHERS) return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("unknown_lib"))
            val body = call.receive<SignInRequest>()
            val result = auth.signIn(body.email, body.password, lib)
            val timing = SpikeTiming(
                instanceId = stats.instanceId,
                firstRequestOnInstance = firstOnInstance,
                hasher = result.timing.hasher,
                waitMs = result.timing.waitMs,
                hashMs = result.timing.hashMs,
                totalMs = millisBetween(startedAt, System.nanoTime()),
                inFlight = stats.snapshot().inFlight,
                memory = processMemory(),
            )
            when (result) {
                is SignIn.Success -> call.respond(TokenResponse.of(result.tokens, timing))
                is SignIn.MfaRequired -> call.respond(MfaResponse(mfaRequired = true, mfaToken = result.mfaToken, spike = timing))
                is SignIn.Refused -> call.respond(HttpStatusCode.Unauthorized, ErrorBody("invalid_credentials", timing))
            }
        }

        post("/v1/auth/mfa") {
            val body = call.receive<MfaRequest>()
            val pair = auth.completeMfa(body.mfaToken, body.code)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorBody("invalid_credentials"))
            call.respond(TokenResponse.of(pair))
        }

        // In the service the refresh token is an HttpOnly cookie for the web app; a body field here.
        post("/v1/auth/refresh") {
            val body = call.receive<RefreshRequest>()
            val pair = auth.refresh(body.refreshToken)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorBody("invalid_refresh_token"))
            call.respond(TokenResponse.of(pair))
        }

        post("/v1/auth/sign-out") {
            auth.signOut(call.receive<RefreshRequest>().refreshToken)
            call.respond(HttpStatusCode.NoContent)
        }

        // Who the caller is comes from the account store on every call, not from the token.
        authenticate(Audience.STAFF) {
            get("/v1/staff/me") {
                val principal = call.principal<JWTPrincipal>()!!
                val account = auth.account(principal.subject.orEmpty())
                if (account == null || !account.active || account.role == null) {
                    return@get call.respond(HttpStatusCode.Unauthorized, ErrorBody("invalid_token"))
                }
                call.respond(Me(account.id, account.restaurant, account.role.name.lowercase(), amr(principal)))
            }
        }
        authenticate(Audience.ADMIN) {
            get("/v1/admin/me") {
                val principal = call.principal<JWTPrincipal>()!!
                val account = auth.account(principal.subject.orEmpty())
                if (account == null || !account.active) {
                    return@get call.respond(HttpStatusCode.Unauthorized, ErrorBody("invalid_token"))
                }
                if (!account.platformAdmin) return@get call.respond(HttpStatusCode.Forbidden, ErrorBody("forbidden"))
                if ("otp" !in amr(principal)) return@get call.respond(HttpStatusCode.Forbidden, ErrorBody("mfa_required"))
                call.respond(Me(account.id, null, "platform_admin", amr(principal)))
            }
        }

        // Spike only: tokens for the cases the sign-in flow can't produce on purpose.
        get("/spike/test-tokens") {
            call.respond(tokens.testCases(staffSubject = Seeds.WAITER_ID, adminSubject = Seeds.ADMIN_ID))
        }
    }
}

private fun amr(principal: JWTPrincipal): List<String> =
    principal.payload.getClaim("amr").asList(String::class.java).orEmpty()

/** Resident and peak memory of this process, from /proc on Linux (Vercel); null elsewhere. */
private fun processMemory(): ProcessMemory? {
    val status = File("/proc/self/status").takeIf { it.canRead() }?.readLines() ?: return null
    fun kb(key: String) = status.firstOrNull { it.startsWith("$key:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
    return ProcessMemory(rssMb = kb("VmRSS")?.div(1024), peakRssMb = kb("VmHWM")?.div(1024))
}

val HASHERS = setOf("bc", "p4j")
private const val MB = 1024 * 1024

@Serializable
data class SignInRequest(val email: String, val password: String)

@Serializable
data class MfaRequest(@SerialName("mfa_token") val mfaToken: String, val code: String)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    val spike: SpikeTiming? = null,
) {
    companion object {
        fun of(pair: TokenPair, timing: SpikeTiming? = null) = TokenResponse(pair.accessToken, pair.refreshToken, pair.expiresIn, timing)
    }
}

@Serializable
data class MfaResponse(
    @SerialName("mfa_required") val mfaRequired: Boolean,
    @SerialName("mfa_token") val mfaToken: String,
    val spike: SpikeTiming,
)

@Serializable
data class ErrorBody(val error: String, val spike: SpikeTiming? = null)

@Serializable
data class Me(val id: String, val restaurant: String?, val role: String, val amr: List<String>)

/** Spike only: where the sign-in time went, measured inside the function. */
@Serializable
data class SpikeTiming(
    val instanceId: String,
    val firstRequestOnInstance: Boolean,
    val hasher: String,
    val waitMs: Double,
    val hashMs: Double,
    val totalMs: Double,
    val inFlight: Int,
    /** Read after totalMs, so it isn't counted in it. */
    val memory: ProcessMemory?,
)

@Serializable
data class ProcessMemory(val rssMb: Long?, val peakRssMb: Long?)

@Serializable
data class Info(
    val instanceId: String,
    val firstRequestOnInstance: Boolean,
    val startupMs: Long?,
    val uptimeMs: Long,
    val requestsServed: Long,
    val region: String?,
    val cpus: Int,
    val maxHeapMb: Long,
    val heapUsedMb: Long,
    val memory: ProcessMemory?,
    val javaVersion: String,
)
