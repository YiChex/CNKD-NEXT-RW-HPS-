/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.file.plugin

import net.rwhps.server.data.bean.internal.BeanPluginInfo
import net.rwhps.server.func.ConsSeq
import net.rwhps.server.game.event.EventGlobalManage
import net.rwhps.server.game.event.EventManage
import net.rwhps.server.game.event.core.AbstractGlobalEventImpl
import net.rwhps.server.game.event.core.EventListenerHost
import net.rwhps.server.plugin.Plugin
import net.rwhps.server.plugin.PluginLoadData
import net.rwhps.server.plugin.PluginDiagnostics
import net.rwhps.server.plugin.PluginRuntimeState
import net.rwhps.server.plugin.PluginsLoad.Companion.addPluginClass
import net.rwhps.server.plugin.PluginsLoad.Companion.resultPluginData
import net.rwhps.server.plugin.v2.PluginEventDispatcher
import net.rwhps.server.plugin.v2.V2PluginLogRuntime
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.annotations.DidNotFinish
import net.rwhps.server.util.annotations.mark.PrivateMark
import net.rwhps.server.util.concurrent.fature.AbstractFuture
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.game.command.CommandHandler
import net.rwhps.server.util.log.Log
import java.util.concurrent.ConcurrentHashMap

/** Plugin manager for restart-scoped trusted plugins. */
@Suppress("UNUSED", "UNUSED_PARAMETER")
object PluginManage {
    private val pluginGlobalEventManage = EventGlobalManage()
    private var pluginData: Seq<PluginLoadData>? = null

    val loadSize: Int
        get() = pluginData?.size ?: 0

    internal fun runGlobalEventManage(abstractGlobalEvent: AbstractGlobalEventImpl): AbstractFuture<*> {
        return pluginGlobalEventManage.fire(abstractGlobalEvent)
    }

    internal fun addGlobalEventManage(eventListenerHost: EventListenerHost) {
        pluginGlobalEventManage.registerListener(eventListenerHost)
    }

    fun run(cons: ConsSeq<PluginLoadData>) {
        pluginSnapshot().forEach { cons(it) }
    }

    /** Returns bounded discovery/load diagnostics for the current restart. */
    fun buildDiagnosticsReport(pluginFileOrId: String? = null): List<String> {
        return PluginDiagnostics.buildReport(pluginFileOrId)
    }

    /** Operator-facing V2 callback health and per-plugin event queue state. */
    fun buildHealthReport(pluginId: String? = null): List<String> {
        val requested = pluginId?.trim()?.takeIf { it.isNotBlank() }
        val plugins = pluginSnapshot().filter { plugin ->
            plugin.apiVersion == 2 && (requested == null || plugin.internalName.equals(requested, ignoreCase = true))
        }
        return buildList {
            add("pluginHealth.mode=per_plugin_single_thread_bounded")
            add("pluginHealth.defaultEventQueueCapacity=${PluginEventDispatcher.DEFAULT_QUEUE_CAPACITY}")
            add("pluginHealth.slowEventThresholdMs=${net.rwhps.server.plugin.PluginRuntimeMetrics.SLOW_EVENT_CALLBACK_MILLIS}")
            add("pluginHealth.count=${plugins.size}")
            plugins.forEachIndexed { index, plugin ->
                val health = plugin.runtimeMetrics?.healthSnapshot()
                val level = when {
                    plugin.state == PluginRuntimeState.FAILED -> "FAILED"
                    health != null -> health.level
                    else -> "UNKNOWN"
                }
                add("pluginHealth[$index].id=${plugin.internalName}")
                add("pluginHealth[$index].state=${plugin.state}")
                add("pluginHealth[$index].level=$level")
                add("pluginHealth[$index].reasons=${health?.reasons?.joinToString(",")?.ifBlank { "none" } ?: "metrics_unavailable"}")
                if (health != null) {
                    add("pluginHealth[$index].eventQueue=${health.eventQueueDepth}/${health.eventQueueMaxDepth}/${health.eventQueueCapacity}")
                    add("pluginHealth[$index].events=dropped:${health.droppedEvents},slow:${health.slowEventCallbacks},callbackFailures:${health.eventCallbackFailures},shutdownTimeouts:${health.eventDispatcherShutdownTimeouts}")
                    add("pluginHealth[$index].eventDurationUs=${health.lastEventCallbackDurationMicros}/${health.maxEventCallbackDurationMicros}")
                    add("pluginHealth[$index].otherFailures=command:${health.commandFailures},scheduler:${health.schedulerFailures}")
                    add("pluginHealth[$index].last=${health.lastCallback}@${health.lastCallbackAtMillis}")
                }
            }
            if (plugins.isEmpty()) {
                add("pluginHealth.note=${if (requested == null) "no V2 plugins loaded" else "V2 plugin not found: $requested"}")
            }
        }
    }

    fun init(fileUtils: FileUtils) {
        V2PluginLogRuntime.start()
        try {
            pluginData = resultPluginData(fileUtils)
        } catch (error: Throwable) {
            V2PluginLogRuntime.shutdown()
            throw error
        }
    }

    internal fun addPluginClass(pluginInfo: BeanPluginInfo, main: Plugin, mkdir: Boolean, skip: Boolean) {
        addPluginClass(pluginInfo.name, pluginInfo.internalName, pluginInfo.author, pluginInfo.description, pluginInfo.version, main, mkdir, skip)
    }

    fun addPluginClass(
        name: String,
        internalName: String,
        author: String,
        description: String,
        version: String,
        main: Plugin,
        mkdir: Boolean,
        skip: Boolean = false
    ) {
        val plugins = requireNotNull(pluginData) {
            "PluginManage.init must be called before plugins are added"
        }
        val duplicate = plugins.firstOrNull { it.internalName.equals(internalName, ignoreCase = true) }
        if (duplicate != null) {
            val reason = "Duplicate plugin internalName '$internalName' conflicts with '${duplicate.name}'"
            if (skip && !duplicate.isBuiltInPlugin) {
                duplicate.recordFailure("identity", IllegalArgumentException(reason))
                Log.error("$reason; the external plugin was isolated in favor of the built-in plugin")
            } else {
                throw IllegalArgumentException(reason)
            }
        }
        addPluginClass(name, internalName, author, description, version, main, mkdir, skip, plugins)
    }

    fun runOnEnable() {
        PluginLifecycleExecutor.runPhase(pluginSnapshot(), "onEnable", PluginRuntimeState.ENABLED) { it.onEnable() }
    }

    fun runRegisterCoreCommands(handler: CommandHandler) {
        PluginLifecycleExecutor.runPhase(pluginSnapshot(), "registerCoreCommands") { plugin ->
            handler.runRegistrationTransaction { plugin.registerCoreCommands(handler) }
        }
    }

    fun runRegisterServerCommands(handler: CommandHandler) {
        PluginLifecycleExecutor.runPhase(pluginSnapshot(), "registerServerCommands") { plugin ->
            handler.runRegistrationTransaction { plugin.registerServerCommands(handler) }
        }
    }

    @PrivateMark
    fun runRegisterRelayCommands(handler: CommandHandler) {
        PluginLifecycleExecutor.runPhase(pluginSnapshot(), "registerRelayCommands") { plugin ->
            handler.runRegistrationTransaction { plugin.registerRelayCommands(handler) }
        }
    }

    fun runRegisterServerClientCommands(handler: CommandHandler) {
        PluginLifecycleExecutor.runPhase(pluginSnapshot(), "registerServerClientCommands") { plugin ->
            handler.runRegistrationTransaction { plugin.registerServerClientCommands(handler) }
        }
    }

    @PrivateMark
    fun runRegisterRelayClientCommands(handler: CommandHandler) {
        PluginLifecycleExecutor.runPhase(pluginSnapshot(), "registerRelayClientCommands") { plugin ->
            handler.runRegistrationTransaction { plugin.registerRelayClientCommands(handler) }
        }
    }

    fun runRegisterEvents(hessLoadID: String, eventManage: EventManage) {
        /*
         * D-20: hessLoadID used to be accepted and ignored, so a second
         * ServerHessLoadEvent for the same Hess instance registered every plugin's
         * event listeners a second time. Register at most once per Hess instance.
         */
        if (hessLoadID.isNotBlank() && !registeredEventHessLoadIds.add(hessLoadID)) {
            Log.debug("Plugin event listeners already registered for Hess instance: $hessLoadID")
            return
        }
        PluginLifecycleExecutor.runPhase(pluginSnapshot(), "registerEvents") { it.registerEvents(eventManage) }
    }

    fun runRegisterGlobalEvents() {
        PluginLifecycleExecutor.runPhase(pluginSnapshot(), "registerGlobalEvents") { it.registerGlobalEvents(pluginGlobalEventManage) }
    }

    fun runInit() {
        val plugins = pluginSnapshot()
        PluginLifecycleExecutor.requireNoBuiltInFailure(plugins)
        PluginLifecycleExecutor.runPhase(plugins, "init", PluginRuntimeState.ACTIVE) { it.init() }
    }

    fun runOnDisable() {
        try {
            PluginLifecycleExecutor.shutdown(pluginSnapshot())
        } finally {
            V2PluginLogRuntime.shutdown()
        }
    }

    /**
     * D-16: this used to return `Unit` while doing nothing but logging a warning, so
     * callers could not tell that nothing happened. It now reports failure explicitly.
     *
     * Hot unload is intentionally unsupported: plugin class loaders are never closed
     * while the process lives, so unloading would leak classes and break the
     * fail-closed supply-chain lock guarantee.
     *
     * @return always `false`; restart the server to change the plugin set
     */
    @DidNotFinish
    fun removePlugin(name: String): Boolean {
        Log.warn("Plugin hot unload is not supported safely; restart the server to unload '$name'.")
        return false
    }

    private fun pluginSnapshot(): List<PluginLoadData> = pluginData?.toList().orEmpty()

    /** Hess instances whose plugin event listeners have already been registered (D-20). */
    private val registeredEventHessLoadIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
}

/** Isolates external plugin failures while keeping built-in failures fail-fast. */
internal object PluginLifecycleExecutor {
    fun requireNoBuiltInFailure(plugins: List<PluginLoadData>) {
        val failed = plugins.firstOrNull { it.isBuiltInPlugin && it.state == PluginRuntimeState.FAILED } ?: return
        throw IllegalStateException(
            "Built-in plugin '${failed.internalName}' previously failed during ${failed.failurePhase ?: "unknown"}: " +
                (failed.failureMessage ?: "unknown error")
        )
    }

    fun runPhase(
        plugins: List<PluginLoadData>,
        phase: String,
        nextState: PluginRuntimeState? = null,
        action: (Plugin) -> Unit
    ) {
        var builtInFailure: Pair<PluginLoadData, Throwable>? = null
        plugins.forEach { plugin ->
            if (!plugin.isRunnable()) {
                return@forEach
            }

            val startedAt = System.nanoTime()
            try {
                action(plugin.main)
                plugin.recordSuccess(phase, System.nanoTime() - startedAt, nextState)
            } catch (error: Throwable) {
                rethrowFatal(error)
                plugin.recordFailure(phase, error, System.nanoTime() - startedAt)
                Log.error("Plugin '${plugin.internalName}' failed during $phase and was isolated", error)
                if (plugin.isBuiltInPlugin && builtInFailure == null) {
                    builtInFailure = plugin to error
                }
            }
        }

        builtInFailure?.let { (plugin, error) ->
            throw IllegalStateException("Built-in plugin '${plugin.internalName}' failed during $phase", error)
        }
    }

    fun shutdown(plugins: List<PluginLoadData>) {
        val disabling = ArrayList<Pair<PluginLoadData, Long>>(plugins.size)
        plugins.asReversed().forEach { plugin ->
            if (!plugin.tryBeginDisable()) {
                return@forEach
            }

            val startedAt = System.nanoTime()
            try {
                plugin.main.onDisable()
            } catch (error: Throwable) {
                rethrowFatal(error)
                plugin.recordFailure("onDisable", error, System.nanoTime() - startedAt)
                Log.error("Plugin '${plugin.internalName}' failed during onDisable; shutdown will continue", error)
            }
            disabling.add(plugin to startedAt)
        }

        // All callbacks complete before any plugin class loader is closed.
        disabling.forEach { (plugin, startedAt) ->
            try {
                plugin.closeClassLoader()
            } catch (error: Throwable) {
                rethrowFatal(error)
                plugin.recordFailure("classLoader.close", error, System.nanoTime() - startedAt)
                Log.error("Plugin '${plugin.internalName}' class loader could not be closed", error)
            } finally {
                plugin.markDisabled(System.nanoTime() - startedAt)
            }
        }
    }

    private fun rethrowFatal(error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath) {
            throw error
        }
    }
}
