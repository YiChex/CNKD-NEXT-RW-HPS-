package net.rwhps.server.plugin.v2

import net.rwhps.server.data.bean.internal.BeanPluginInfo
import net.rwhps.server.plugin.PluginLoadData
import net.rwhps.server.plugin.PluginRuntimeMetrics
import net.rwhps.server.plugin.V2PluginCapabilities
import net.rwhps.server.plugin.api.CnkdPlugin
import net.rwhps.server.util.file.FileUtils
import java.net.URLClassLoader

internal class V2PluginRuntime(
    val descriptor: BeanPluginInfo,
    val plugin: CnkdPlugin,
    val classLoader: URLClassLoader,
    dataDirectory: FileUtils,
    pluginLogSink: PluginLogSink = DiscardingPluginLogSink
) {
    val runtimeMetrics = PluginRuntimeMetrics()
    val scope = PluginResourceScope(descriptor.internalName, pluginLogSink, runtimeMetrics)
    val context = V2PluginContext(descriptor, dataDirectory.file.toPath(), scope, classLoader, runtimeMetrics, pluginLogSink)

    fun toLoadData(): PluginLoadData {
        val adapter = V2PluginAdapter(this)
        return PluginLoadData(
            name = descriptor.name,
            internalName = descriptor.internalName,
            author = descriptor.author,
            description = descriptor.description,
            version = descriptor.version,
            main = adapter,
            mkdir = false,
            classLoader = classLoader,
            apiVersion = 2,
            capabilities = V2PluginCapabilities.normalize(descriptor.capabilities)
        ).also { it.runtimeMetrics = runtimeMetrics }
    }
}
