package net.rwhps.server.plugin.v2

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.file.FileUtils

/**
 * Process-scoped owner for the V2 plugin log writer. Plugin loading starts this
 * service before external callbacks can run, then the plugin lifecycle closes it.
 */
internal object V2PluginLogRuntime {
    private val lock = Any()

    @Volatile
    private var service: PluginLogService? = null

    fun start(): PluginLogService {
        synchronized(lock) {
            service?.let { return it }
            return PluginLogService(
                logDirectory = FileUtils.getFolder(Data.ServerLogPath).toFolder("plugins").file.toPath(),
                configProvider = { Data.config.pluginLogging },
                coreLogLevelProvider = { Data.config.log },
                consoleSink = { line -> Data.privateOut.println(line) }
            ).also { service = it }
        }
    }

    fun requireService(): PluginLogService {
        return checkNotNull(service) {
            "V2PluginLogRuntime.start must be called before V2 plugins are loaded"
        }
    }

    fun shutdown() {
        val current = synchronized(lock) {
            val active = service
            service = null
            active
        }
        current?.close()
    }

    internal fun diagnostics(): PluginLogDiagnostics? = service?.diagnostics()
}
