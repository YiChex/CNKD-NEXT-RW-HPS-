package net.rwhps.server.plugin.v2

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

/** A stuck trusted plugin can fill its own queue, never the network worker or other plugins. */
internal class PluginCommandDispatcher(pluginId: String): AutoCloseable {
    private val worker = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(32), { job -> Thread(job, "PluginV2-Command-$pluginId").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }

    fun submit(task: Runnable): Future<*>? = try { worker.submit(task) } catch (_: RejectedExecutionException) { null }

    override fun close() { worker.shutdownNow() }
}
