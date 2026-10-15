package spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceStatsTest {
    @Test
    fun `claimFirstRequest - called twice - true only the first time`() {
        val stats = InstanceStats()

        assertTrue(stats.claimFirstRequest())
        assertFalse(stats.claimFirstRequest())
    }

    @Test
    fun `snapshot - overlapping requests - tracks in flight and the maximum`() {
        val stats = InstanceStats()
        stats.requestStarted()
        stats.requestStarted()
        stats.requestFinished()
        stats.requestStarted()

        val snapshot = stats.snapshot()

        assertEquals(3, snapshot.requestsServed)
        assertEquals(2, snapshot.inFlight)
        assertEquals(2, snapshot.maxInFlightSeen)
    }

    @Test
    fun `snapshot - before and after ready - startupMs measured from the JVM start`() {
        var now = 0L
        val stats = InstanceStats(now = { now })
        assertNull(stats.snapshot().startupMs)

        now = stats.jvmStartedAtMs + 1_234
        stats.markReady()

        assertEquals(1_234, stats.snapshot().startupMs)
    }
}
