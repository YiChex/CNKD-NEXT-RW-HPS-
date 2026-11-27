package net.rwhps.server.plugin.internal.headless.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LatencySampleWindowTest {
    @Test
    fun `reports nearest-rank percentiles for a complete sample set`() {
        val window = LatencySampleWindow(capacity = 32)
        (1L..20L).forEach(window::record)

        val snapshot = window.snapshot()

        assertEquals(20, snapshot.sampleCount)
        assertEquals(10L, snapshot.p50Nanos)
        assertEquals(19L, snapshot.p95Nanos)
        assertEquals(20L, snapshot.maxNanos)
    }

    @Test
    fun `retains only the most recent bounded latency samples`() {
        val window = LatencySampleWindow(capacity = 4)
        (1L..5L).forEach(window::record)

        val snapshot = window.snapshot()

        assertEquals(4, snapshot.sampleCount)
        assertEquals(3L, snapshot.p50Nanos)
        assertEquals(5L, snapshot.p95Nanos)
        assertEquals(5L, snapshot.maxNanos)
    }

    @Test
    fun `clear removes prior samples`() {
        val window = LatencySampleWindow(capacity = 4)
        window.record(123L)
        window.clear()

        assertEquals(LatencySampleWindow.Snapshot.EMPTY, window.snapshot())
    }
}
