package net.rwhps.server.plugin.v2

import net.rwhps.server.plugin.PluginRuntimeMetrics
import net.rwhps.server.util.concurrent.threads.ThreadFactoryName
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Per-plugin ordered event queue. Rejection never runs work on the publishing thread. */
internal class PluginEventDispatcher(
    private val pluginId: String,
    private val metrics: PluginRuntimeMetrics,
    private val logSink: PluginLogSink = DiscardingPluginLogSink,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY
): AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val rejectionCount = AtomicLong()
    private val executor: ThreadPoolExecutor

    init {
        require(queueCapacity in 1..MAX_QUEUE_CAPACITY) {
            "Plugin event queue capacity must be within 1..$MAX_QUEUE_CAPACITY"
        }
        metrics.configureEventQueue(queueCapacity)
        executor = ThreadPoolExecutor(
            1,
            1,
            IDLE_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(queueCapacity),
            daemonThreadFactory("PluginV2-Event-${safeThreadName(pluginId)}-"),
            ThreadPoolExecutor.AbortPolicy()
        ).apply {
            allowCoreThreadTimeOut(true)
        }
    }

    fun dispatch(task: Runnable): Boolean {
        if (closed.get()) {
            metrics.recordEventNotificationDropped()
            return false
        }
        return try {
            executor.execute {
                metrics.recordEventDispatchStarted(executor.queue.size)
                try {
                    task.run()
                } finally {
                    metrics.recordEventDispatchFinished(executor.queue.size)
                }
            }
            metrics.recordEventNotificationQueued(executor.queue.size)
            true
        } catch (_: RejectedExecutionException) {
            if (closed.get() || executor.isShutdown) {
                metrics.recordEventNotificationDropped()
            } else {
                recordRejected()
            }
            false
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        val cancelled = executor.shutdownNow().size
        if (cancelled > 0) {
            metrics.recordEventNotificationDropped(cancelled.toLong())
        }
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                metrics.recordEventDispatcherShutdownTimeout()
                logSink.warn(
                    pluginId,
                    "Event dispatcher did not stop within ${SHUTDOWN_WAIT_MILLIS}ms; " +
                        "the daemon worker will not block process exit",
                    metrics
                )
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            metrics.recordEventDispatcherShutdownTimeout()
        }
        metrics.recordEventDispatchFinished(0)
    }

    private fun recordRejected() {
        metrics.recordEventNotificationDropped()
        val count = rejectionCount.incrementAndGet()
        if (count == 1L || count % REJECTION_LOG_INTERVAL == 0L) {
            logSink.warn(
                pluginId,
                "Event notification dropped because this plugin queue is full " +
                    "capacity=${metrics.eventQueueCapacity()} drops=$count",
                metrics
            )
        }
    }

    private fun safeThreadName(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48).ifBlank { "plugin" }
    }

    private fun daemonThreadFactory(name: String): ThreadFactory {
        val delegate = ThreadFactoryName.nameThreadFactory(name)
        return ThreadFactory { task ->
            delegate.newThread(task).apply { isDaemon = true }
        }
    }

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 64
        private const val MAX_QUEUE_CAPACITY = 1024
        private const val IDLE_TIMEOUT_SECONDS = 30L
        private const val SHUTDOWN_WAIT_MILLIS = 500L
        private const val REJECTION_LOG_INTERVAL = 25L
    }
}
