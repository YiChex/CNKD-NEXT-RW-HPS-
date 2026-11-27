package net.rwhps.server.plugin.v2

import net.rwhps.server.plugin.PluginRuntimeMetrics

/** Internal logging boundary used by V2 plugin contexts. */
internal interface PluginLogSink {
    fun info(pluginId: String, message: String, metrics: PluginRuntimeMetrics? = null)
    fun warn(pluginId: String, message: String, metrics: PluginRuntimeMetrics? = null)
    fun error(pluginId: String, message: String, error: Throwable? = null, metrics: PluginRuntimeMetrics? = null)
}

/** Keeps isolated unit tests and unconfigured contexts free of filesystem side effects. */
internal object DiscardingPluginLogSink : PluginLogSink {
    override fun info(pluginId: String, message: String, metrics: PluginRuntimeMetrics?) = Unit
    override fun warn(pluginId: String, message: String, metrics: PluginRuntimeMetrics?) = Unit
    override fun error(pluginId: String, message: String, error: Throwable?, metrics: PluginRuntimeMetrics?) = Unit
}
