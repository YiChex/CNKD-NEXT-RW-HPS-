package net.rwhps.server.plugin.v2

import net.rwhps.server.plugin.PluginRuntimeMetrics
import net.rwhps.server.plugin.api.Registration
import java.util.concurrent.atomic.AtomicBoolean

internal class PluginResourceScope(
    private val pluginId: String,
    private val pluginLogSink: PluginLogSink = DiscardingPluginLogSink,
    private val runtimeMetrics: PluginRuntimeMetrics? = null
): AutoCloseable {
    private val accepting = AtomicBoolean(true)
    private val closed = AtomicBoolean(false)
    private val resources = ArrayList<AutoCloseable>()

    @Synchronized
    fun manage(resource: AutoCloseable) {
        if (!accepting.get() || closed.get()) {
            try {
                resource.close()
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) {
                    throw error
                }
                pluginLogSink.error(pluginId, "Late resource cleanup failed", error, runtimeMetrics)
            }
            throw IllegalStateException("Plugin scope '$pluginId' is already closed")
        }
        resources.add(resource)
    }

    fun isActive(): Boolean = accepting.get() && !closed.get()

    @Synchronized
    fun stopAccepting() {
        accepting.set(false)
    }

    fun registration(closeAction: () -> Unit): Registration {
        val registration = ManagedRegistration(closeAction)
        try {
            manage(registration)
        } catch (error: Throwable) {
            registration.close()
            throw error
        }
        return registration
    }

    override fun close() {
        accepting.set(false)
        if (!closed.compareAndSet(false, true)) {
            return
        }
        val snapshot = synchronized(this) {
            resources.asReversed().toList().also { resources.clear() }
        }
        snapshot.forEachIndexed { index, resource ->
            try {
                resource.close()
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) {
                    throw error
                }
                pluginLogSink.error(pluginId, "Resource cleanup #$index failed", error, runtimeMetrics)
            }
        }
    }

    private class ManagedRegistration(private val closeAction: () -> Unit): Registration {
        private val active = AtomicBoolean(true)

        override fun isActive(): Boolean = active.get()

        override fun close() {
            if (active.compareAndSet(true, false)) {
                closeAction()
            }
        }
    }
}
