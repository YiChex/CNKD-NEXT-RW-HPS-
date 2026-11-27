package net.rwhps.server.net.core

import net.rwhps.server.game.event.global.NetConnectCloseEvent
import net.rwhps.server.util.file.plugin.PluginManage
import net.rwhps.server.util.log.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** Transport cleanup never waits for third-party shutdown listeners. */
internal object CloseEventNotifier {
    private val rejected = AtomicLong()
    private val workers = ThreadPoolExecutor(1, 2, 30L, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(128), { job -> Thread(job, "Net-Close-Notification").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }

    fun notifyClosed(connection: ConnectionAgreement) {
        try {
            workers.execute {
                val event = PluginManage.runGlobalEventManage(NetConnectCloseEvent(connection))
                try {
                    if (!event.await(5, TimeUnit.SECONDS)) {
                        Log.warn("[NetLifecycle] Close listener timed out after transport cleanup")
                        // Keep this daemon worker occupied until the listener actually exits.
                        // Cancellation does not interrupt arbitrary blocking V1 code; submitting
                        // replacements would otherwise accumulate unbounded Dispatchers.IO work.
                        while (!event.isDone) Thread.sleep(250)
                    } else event.cause()?.let { Log.warn("[NetLifecycle] Close listener failed after transport cleanup", it) }
                } catch (error: Exception) {
                    event.cancel(true)
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    Log.warn("[NetLifecycle] Close notification failed after transport cleanup", error)
                }
            }
        } catch (_: RejectedExecutionException) {
            val count = rejected.incrementAndGet()
            if (count == 1L || count % 100L == 0L) Log.warn("[NetLifecycle] Close notification queue full; transport already cleaned, dropped=$count")
        }
    }
}
