package net.rwhps.server.plugin.internal.headless.support
import net.rwhps.server.core.thread.CallTimeTask
import net.rwhps.server.core.thread.Threads
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LatencyHeartbeatConcurrencyTest {
    @Test fun `simultaneous joins register one heartbeat job`() {
        Threads.closeTimeTask(CallTimeTask.LatencyHeartbeatTask)
        val pool=Executors.newFixedThreadPool(24)
        try {
            val barrier=CyclicBarrier(24)
            val tasks=(1..24).map{pool.submit{barrier.await(5,TimeUnit.SECONDS);StartModeLatencyHeartbeatSupport.ensureStarted()}}
            tasks.forEach{it.get(10,TimeUnit.SECONDS)}
            assertTrue(Threads.containsTimeTask(CallTimeTask.LatencyHeartbeatTask))
        }finally{pool.shutdownNow();Threads.closeTimeTask(CallTimeTask.LatencyHeartbeatTask)}
    }
}
