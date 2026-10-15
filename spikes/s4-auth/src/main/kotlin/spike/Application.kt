package spike

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.time.Clock

/**
 * Starts the spike server on `PORT` (default 8080). Reads `SPIKE_KEYS` (scripts/setup-env.mjs;
 * generated in memory if unset), `SPIKE_ISSUER` and `HASH_PERMITS` (default 4, architecture §7).
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val permits = System.getenv("HASH_PERMITS")?.toIntOrNull() ?: DEFAULT_HASH_PERMITS
    val issuer = System.getenv("SPIKE_ISSUER") ?: DEFAULT_ISSUER
    val clock = Clock.systemUTC()
    val tokens = Tokens(SpikeKeys.fromEnv(System.getenv("SPIKE_KEYS")), issuer, clock)
    val auth = AuthService(
        accounts = Seeds.accounts(),
        hashers = listOf(BouncyCastleArgon2(), Password4jArgon2()).associateBy { it.name },
        gate = HashGate(permits),
        tokens = tokens,
        clock = clock,
        dummyPhc = Seeds.DUMMY_PHC,
    )
    embeddedServer(Netty, port = port, host = "0.0.0.0") { spikeModule(InstanceStats(), auth, tokens) }
        .start(wait = true)
}

private const val DEFAULT_PORT = 8080
private const val DEFAULT_HASH_PERMITS = 4
private const val DEFAULT_ISSUER = "https://restaurant-s4-spike.example"
