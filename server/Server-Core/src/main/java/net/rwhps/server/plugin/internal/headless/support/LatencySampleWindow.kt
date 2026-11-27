package net.rwhps.server.plugin.internal.headless.support

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/** Bounded, recent-only latency samples used by explicit diagnostic commands. */
internal class LatencySampleWindow(private val capacity: Int = DEFAULT_CAPACITY) {
    private val samples: AtomicLongArray
    private val writeCount = AtomicLong(0L)

    init {
        require(capacity > 0) { "capacity must be positive" }
        samples = AtomicLongArray(capacity)
    }

    fun record(nanos: Long) {
        val slot = Math.floorMod(writeCount.getAndIncrement(), capacity.toLong()).toInt()
        samples.set(slot, nanos.coerceAtLeast(0L))
    }

    fun snapshot(): Snapshot {
        val written = writeCount.get()
        val sampleCount = minOf(written, capacity.toLong()).toInt()
        if (sampleCount == 0) {
            return Snapshot.EMPTY
        }

        val firstSequence = written - sampleCount
        val ordered = LongArray(sampleCount) { offset ->
            val slot = Math.floorMod(firstSequence + offset, capacity.toLong()).toInt()
            samples.get(slot)
        }
        ordered.sort()

        return Snapshot(
            sampleCount = sampleCount,
            p50Nanos = percentile(ordered, 50),
            p95Nanos = percentile(ordered, 95),
            maxNanos = ordered.last()
        )
    }

    fun clear() {
        writeCount.set(0L)
        for (index in 0 until capacity) {
            samples.set(index, 0L)
        }
    }

    private fun percentile(sorted: LongArray, percentile: Int): Long {
        val rank = ((sorted.size.toLong() * percentile + 99L) / 100L - 1L)
            .coerceIn(0L, (sorted.size - 1).toLong())
        return sorted[rank.toInt()]
    }

    data class Snapshot(
        val sampleCount: Int,
        val p50Nanos: Long,
        val p95Nanos: Long,
        val maxNanos: Long
    ) {
        companion object {
            val EMPTY = Snapshot(0, 0L, 0L, 0L)
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 4_096
    }
}
