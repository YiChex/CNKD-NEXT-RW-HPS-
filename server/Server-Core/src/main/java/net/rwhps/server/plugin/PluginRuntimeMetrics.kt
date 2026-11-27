package net.rwhps.server.plugin

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

internal data class PluginRuntimeHealthSnapshot(
    val level: String,
    val reasons: List<String>,
    val eventQueueDepth: Int,
    val eventQueueMaxDepth: Int,
    val eventQueueCapacity: Int,
    val droppedEvents: Long,
    val slowEventCallbacks: Long,
    val eventCallbackFailures: Long,
    val eventDispatcherShutdownTimeouts: Long,
    val schedulerFailures: Long,
    val commandFailures: Long,
    val lastEventCallbackDurationMicros: Long,
    val maxEventCallbackDurationMicros: Long,
    val lastCallback: String,
    val lastCallbackAtMillis: Long
)

/**
 * Read-only runtime evidence for V2 plugin callbacks.
 *
 * These counters are server-owned. They are not exposed through the plugin API and do not
 * participate in game-command processing.
 */
internal class PluginRuntimeMetrics {
    private val commandInvocations = AtomicLong()
    private val commandFailures = AtomicLong()
    private val eventNotifications = AtomicLong()
    private val eventCallbacks = AtomicLong()
    private val eventCallbackFailures = AtomicLong()
    private val droppedEventNotifications = AtomicLong()
    private val schedulerCallbacks = AtomicLong()
    private val schedulerFailures = AtomicLong()
    private val pluginLogEmissions = AtomicLong()
    private val pluginLogSuppressed = AtomicLong()
    private val pluginLogTruncated = AtomicLong()
    private val pluginLogFileQueueDrops = AtomicLong()
    private val pluginLogFileFailures = AtomicLong()
    private val eventQueueDepth = AtomicInteger()
    private val eventQueueMaxDepth = AtomicInteger()
    private val eventQueueCapacity = AtomicInteger()
    private val slowEventCallbacks = AtomicLong()
    private val lastEventCallbackDurationNanos = AtomicLong()
    private val maxEventCallbackDurationNanos = AtomicLong()
    private val eventDispatcherShutdownTimeouts = AtomicLong()

    @Volatile
    private var lastCallback = "none"

    @Volatile
    private var lastCallbackAtMillis = 0L

    fun recordCommandSuccess() {
        commandInvocations.incrementAndGet()
        record("command")
    }

    fun recordCommandFailure() {
        commandInvocations.incrementAndGet()
        commandFailures.incrementAndGet()
        record("command-failed")
    }

    fun configureEventQueue(capacity: Int) {
        eventQueueCapacity.set(capacity.coerceAtLeast(0))
    }

    fun recordEventNotificationQueued(queueDepth: Int) {
        eventNotifications.incrementAndGet()
        updateEventQueueDepth(queueDepth)
        record("event-queued")
    }

    fun recordEventDispatchStarted(queueDepth: Int) {
        updateEventQueueDepth(queueDepth)
        record("event-dispatch")
    }

    fun recordEventDispatchFinished(queueDepth: Int) {
        updateEventQueueDepth(queueDepth)
    }

    fun recordEventCallbackSuccess(durationNanos: Long = 0L): Boolean {
        eventCallbacks.incrementAndGet()
        val slowWarning = recordEventDuration(durationNanos)
        record("event-callback")
        return slowWarning
    }

    fun recordEventCallbackFailure(durationNanos: Long = 0L) {
        eventCallbacks.incrementAndGet()
        eventCallbackFailures.incrementAndGet()
        recordEventDuration(durationNanos)
        record("event-callback-failed")
    }

    fun recordEventNotificationDropped(count: Long = 1L) {
        if (count <= 0L) {
            return
        }
        droppedEventNotifications.addAndGet(count)
        record("event-dropped")
    }

    fun recordEventDispatcherShutdownTimeout() {
        eventDispatcherShutdownTimeouts.incrementAndGet()
        record("event-dispatcher-shutdown-timeout")
    }

    fun recordSchedulerSuccess() {
        schedulerCallbacks.incrementAndGet()
        record("scheduler")
    }

    fun recordSchedulerFailure() {
        schedulerCallbacks.incrementAndGet()
        schedulerFailures.incrementAndGet()
        record("scheduler-failed")
    }

    fun recordPluginLogEmission() {
        pluginLogEmissions.incrementAndGet()
        record("plugin-log")
    }

    fun recordPluginLogSuppressed() {
        pluginLogSuppressed.incrementAndGet()
        record("plugin-log-suppressed")
    }

    fun recordPluginLogTruncated() {
        pluginLogTruncated.incrementAndGet()
        record("plugin-log-truncated")
    }

    fun recordPluginLogFileQueueDrop() {
        pluginLogFileQueueDrops.incrementAndGet()
        record("plugin-log-file-queue-drop")
    }

    fun recordPluginLogFileFailure() {
        pluginLogFileFailures.incrementAndGet()
        record("plugin-log-file-failed")
    }

    fun summary(): String {
        val health = healthSnapshot()
        return "health=" + health.level + ", callbacks(command=" + commandInvocations.get() + "/" + commandFailures.get() +
            ", event=" + eventNotifications.get() + "/" + eventCallbacks.get() + "/" + eventCallbackFailures.get() +
            ", eventDropped=" + droppedEventNotifications.get() +
            ", eventQueue=" + eventQueueDepth.get() + "/" + eventQueueMaxDepth.get() + "/" + eventQueueCapacity.get() +
            ", eventSlow=" + slowEventCallbacks.get() +
            ", eventShutdownTimeout=" + eventDispatcherShutdownTimeouts.get() +
            ", eventDurationUs=" + nanosToMicros(lastEventCallbackDurationNanos.get()) + "/" + nanosToMicros(maxEventCallbackDurationNanos.get()) +
            ", scheduler=" + schedulerCallbacks.get() + "/" + schedulerFailures.get() +
            ", logs=" + pluginLogEmissions.get() + "/" + pluginLogSuppressed.get() + "/" + pluginLogTruncated.get() +
            "/" + pluginLogFileQueueDrops.get() + "/" + pluginLogFileFailures.get() +
            ", last=" + lastCallback + "@" + lastCallbackAtMillis + ")"
    }

    internal fun commandInvocationCount(): Long = commandInvocations.get()
    internal fun commandFailureCount(): Long = commandFailures.get()
    internal fun eventNotificationCount(): Long = eventNotifications.get()
    internal fun eventCallbackCount(): Long = eventCallbacks.get()
    internal fun eventCallbackFailureCount(): Long = eventCallbackFailures.get()
    internal fun droppedEventNotificationCount(): Long = droppedEventNotifications.get()
    internal fun schedulerCallbackCount(): Long = schedulerCallbacks.get()
    internal fun schedulerFailureCount(): Long = schedulerFailures.get()
    internal fun pluginLogEmissionCount(): Long = pluginLogEmissions.get()
    internal fun pluginLogSuppressedCount(): Long = pluginLogSuppressed.get()
    internal fun pluginLogTruncatedCount(): Long = pluginLogTruncated.get()
    internal fun pluginLogFileQueueDropCount(): Long = pluginLogFileQueueDrops.get()
    internal fun pluginLogFileFailureCount(): Long = pluginLogFileFailures.get()
    internal fun eventQueueDepth(): Int = eventQueueDepth.get()
    internal fun eventQueueMaxDepth(): Int = eventQueueMaxDepth.get()
    internal fun eventQueueCapacity(): Int = eventQueueCapacity.get()
    internal fun slowEventCallbackCount(): Long = slowEventCallbacks.get()
    internal fun lastEventCallbackDurationMicros(): Long = nanosToMicros(lastEventCallbackDurationNanos.get())
    internal fun maxEventCallbackDurationMicros(): Long = nanosToMicros(maxEventCallbackDurationNanos.get())
    internal fun eventDispatcherShutdownTimeoutCount(): Long = eventDispatcherShutdownTimeouts.get()

    internal fun healthSnapshot(): PluginRuntimeHealthSnapshot {
        val reasons = ArrayList<String>()
        if (droppedEventNotifications.get() > 0L) reasons += "event_dropped"
        if (eventCallbackFailures.get() > 0L) reasons += "event_callback_failed"
        if (schedulerFailures.get() > 0L) reasons += "scheduler_failed"
        if (pluginLogFileFailures.get() > 0L) reasons += "plugin_log_file_failed"
        if (slowEventCallbacks.get() > 0L) reasons += "event_callback_slow"
        if (commandFailures.get() > 0L) reasons += "command_failed"
        if (pluginLogFileQueueDrops.get() > 0L) reasons += "plugin_log_queue_dropped"
        if (eventDispatcherShutdownTimeouts.get() > 0L) reasons += "event_dispatcher_shutdown_timeout"
        val degradedReasons = setOf(
            "event_dropped",
            "event_callback_failed",
            "scheduler_failed",
            "plugin_log_file_failed",
            "event_dispatcher_shutdown_timeout"
        )
        val level = when {
            reasons.any { it in degradedReasons } -> "DEGRADED"
            reasons.isNotEmpty() -> "WARN"
            else -> "HEALTHY"
        }
        return PluginRuntimeHealthSnapshot(
            level = level,
            reasons = reasons,
            eventQueueDepth = eventQueueDepth.get(),
            eventQueueMaxDepth = eventQueueMaxDepth.get(),
            eventQueueCapacity = eventQueueCapacity.get(),
            droppedEvents = droppedEventNotifications.get(),
            slowEventCallbacks = slowEventCallbacks.get(),
            eventCallbackFailures = eventCallbackFailures.get(),
            eventDispatcherShutdownTimeouts = eventDispatcherShutdownTimeouts.get(),
            schedulerFailures = schedulerFailures.get(),
            commandFailures = commandFailures.get(),
            lastEventCallbackDurationMicros = nanosToMicros(lastEventCallbackDurationNanos.get()),
            maxEventCallbackDurationMicros = nanosToMicros(maxEventCallbackDurationNanos.get()),
            lastCallback = lastCallback,
            lastCallbackAtMillis = lastCallbackAtMillis
        )
    }

    private fun updateEventQueueDepth(depth: Int) {
        val normalized = depth.coerceAtLeast(0)
        eventQueueDepth.set(normalized)
        while (true) {
            val previous = eventQueueMaxDepth.get()
            if (normalized <= previous || eventQueueMaxDepth.compareAndSet(previous, normalized)) {
                return
            }
        }
    }

    private fun recordEventDuration(durationNanos: Long): Boolean {
        val normalized = durationNanos.coerceAtLeast(0L)
        lastEventCallbackDurationNanos.set(normalized)
        while (true) {
            val previous = maxEventCallbackDurationNanos.get()
            if (normalized <= previous || maxEventCallbackDurationNanos.compareAndSet(previous, normalized)) {
                break
            }
        }
        if (normalized < SLOW_EVENT_CALLBACK_NANOS) {
            return false
        }
        val count = slowEventCallbacks.incrementAndGet()
        return count == 1L || count % SLOW_WARNING_INTERVAL == 0L
    }

    private fun nanosToMicros(value: Long): Long = value.coerceAtLeast(0L) / 1_000L

    private fun record(callback: String) {
        lastCallback = callback
        lastCallbackAtMillis = System.currentTimeMillis()
    }

    companion object {
        const val SLOW_EVENT_CALLBACK_MILLIS = 100L
        private const val SLOW_EVENT_CALLBACK_NANOS = SLOW_EVENT_CALLBACK_MILLIS * 1_000_000L
        private const val SLOW_WARNING_INTERVAL = 25L
    }
}
