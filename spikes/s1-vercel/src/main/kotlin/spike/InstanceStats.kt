package spike

import java.lang.management.ManagementFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** What one container instance has seen since its JVM started. Thread-safe. */
class InstanceStats(
    private val now: () -> Long = System::currentTimeMillis,
) {
    val instanceId: String = UUID.randomUUID().toString()
    val jvmStartedAtMs: Long = ManagementFactory.getRuntimeMXBean().startTime

    @Volatile
    var readyAtMs: Long? = null
        private set

    private val requests = AtomicLong()
    private val inFlight = AtomicInteger()
    private val maxInFlight = AtomicInteger()
    private val firstRequestServed = AtomicBoolean(false)

    fun markReady() {
        readyAtMs = now()
    }

    /** Call when a request starts. Returns the number in flight, including this one. */
    fun requestStarted(): Int {
        requests.incrementAndGet()
        val current = inFlight.incrementAndGet()
        maxInFlight.accumulateAndGet(current, ::maxOf)
        return current
    }

    fun requestFinished() {
        inFlight.decrementAndGet()
    }

    /** True exactly once per instance: for the first request that asks. */
    fun claimFirstRequest(): Boolean = firstRequestServed.compareAndSet(false, true)

    fun snapshot(): StatsSnapshot = StatsSnapshot(
        requestsServed = requests.get(),
        inFlight = inFlight.get(),
        maxInFlightSeen = maxInFlight.get(),
        uptimeMs = now() - jvmStartedAtMs,
        startupMs = readyAtMs?.let { it - jvmStartedAtMs },
    )

    fun startupSummary(): String =
        "instance=$instanceId startupMs=${snapshot().startupMs}"

    fun shutdownSummary(): String = snapshot().let {
        "instance=$instanceId uptimeMs=${it.uptimeMs} requests=${it.requestsServed} maxInFlight=${it.maxInFlightSeen}"
    }
}

data class StatsSnapshot(
    val requestsServed: Long,
    val inFlight: Int,
    val maxInFlightSeen: Int,
    val uptimeMs: Long,
    val startupMs: Long?,
)
