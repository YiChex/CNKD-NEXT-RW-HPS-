package net.rwhps.server.plugin.v2

import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.bean.internal.BeanPluginInfo
import net.rwhps.server.data.global.Data
import net.rwhps.server.func.StrCons
import net.rwhps.server.game.event.game.ServerGameOverEvent
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.plugin.PluginRuntimeMetrics
import net.rwhps.server.plugin.V2PluginCapabilities
import net.rwhps.server.plugin.api.PlayerSnapshot
import net.rwhps.server.plugin.api.MapSnapshot
import net.rwhps.server.plugin.api.ModSnapshot
import net.rwhps.server.plugin.api.PluginCommands
import net.rwhps.server.plugin.api.PluginContext
import net.rwhps.server.plugin.api.PluginEvents
import net.rwhps.server.plugin.api.PluginLogger
import net.rwhps.server.plugin.api.PluginScheduler
import net.rwhps.server.plugin.api.PluginServer
import net.rwhps.server.plugin.api.RoomSnapshot
import net.rwhps.server.plugin.api.ServerConfigSnapshot
import net.rwhps.server.plugin.api.Registration
import net.rwhps.server.plugin.api.command.CommandExecutor
import net.rwhps.server.plugin.api.command.CommandInvocation
import net.rwhps.server.plugin.api.event.GameEndedEvent
import net.rwhps.server.plugin.api.event.GameStartedEvent
import net.rwhps.server.plugin.api.event.PlayerChatEvent
import net.rwhps.server.plugin.api.event.PlayerJoinedEvent
import net.rwhps.server.plugin.api.event.PlayerLeftEvent
import net.rwhps.server.plugin.api.event.PluginEvent
import net.rwhps.server.plugin.api.event.PluginEventListener
import net.rwhps.server.plugin.internal.headless.support.StartModeRoomPermissionSupport
import net.rwhps.server.util.game.command.CommandHandler
import java.nio.file.Path
import java.util.Collections
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

internal class V2PluginContext(
    private val descriptor: BeanPluginInfo,
    private val dataDirectory: Path,
    private val scope: PluginResourceScope,
    private val pluginClassLoader: ClassLoader = V2PluginContext::class.java.classLoader,
    internal val runtimeMetrics: PluginRuntimeMetrics = PluginRuntimeMetrics(),
    private val pluginLogSink: PluginLogSink = DiscardingPluginLogSink,
    private val stateSnapshotProvider: PluginStateSnapshotProvider = LivePluginStateSnapshotProvider
): PluginContext {
    private val capabilities = Collections.unmodifiableSet(V2PluginCapabilities.normalize(descriptor.capabilities))
    private val active = AtomicBoolean(true)
    private val loggerService = LoggerService(descriptor.internalName, pluginLogSink, runtimeMetrics)
    private val serverService = ServerService()
    private val commandsService = CommandsService(this, scope)
    private val eventsService = EventsService(this, scope)
    private val schedulerService = SchedulerService(descriptor.internalName, scope, pluginClassLoader, runtimeMetrics, pluginLogSink)

    override fun pluginId(): String = descriptor.internalName
    override fun pluginName(): String = descriptor.name
    override fun pluginVersion(): String = descriptor.version
    override fun declaredCapabilities(): Set<String> = capabilities
    override fun dataDirectory(): Path = dataDirectory
    override fun logger(): PluginLogger = loggerService
    override fun server(): PluginServer = serverService
    override fun commands(): PluginCommands = commandsService
    override fun events(): PluginEvents = eventsService
    override fun scheduler(): PluginScheduler {
        requireCapability(V2PluginCapabilities.SCHEDULER)
        return schedulerService
    }
    override fun manage(resource: AutoCloseable) {
        scope.manage(resource)
    }
    private var statisticsService: net.rwhps.server.plugin.api.PluginStatistics? = null
    @Synchronized override fun statistics(): net.rwhps.server.plugin.api.PluginStatistics {
        requireCapability(V2PluginCapabilities.READ_STATISTICS)
        return statisticsService ?: net.rwhps.server.analytics.StatisticsBridge.open(pluginId()).also {
            statisticsService=it; scope.manage(it)
        }
    }

    fun hasCapability(capability: String): Boolean = capability in capabilities

    fun isActive(): Boolean = active.get() && scope.isActive()

    fun deactivate() {
        scope.stopAccepting()
        active.set(false)
        eventsService.stop()
    }

    fun attachConsoleCommands(handler: CommandHandler) = commandsService.attachConsole(handler)
    fun attachPlayerCommands(handler: CommandHandler) = commandsService.attachPlayer(handler)

    fun publishPlayerJoined(player: PlayerHess) {
        if (hasCapability(V2PluginCapabilities.PLAYER_EVENTS)) {
            eventsService.publish(PlayerJoinedEvent(snapshot(player)))
        }
    }

    fun publishPlayerLeft(player: PlayerHess) {
        if (hasCapability(V2PluginCapabilities.PLAYER_EVENTS)) {
            eventsService.publish(PlayerLeftEvent(snapshot(player)))
        }
    }

    fun publishPlayerChat(player: PlayerHess, message: String) {
        if (hasCapability(V2PluginCapabilities.PLAYER_EVENTS)) {
            eventsService.publish(PlayerChatEvent(snapshot(player), message.take(2_000)))
        }
    }

    fun publishGameStarted() {
        if (hasCapability(V2PluginCapabilities.GAME_EVENTS)) {
            eventsService.publish(GameStartedEvent(System.currentTimeMillis()))
        }
    }

    fun publishGameEnded(data: ServerGameOverEvent.GameOverData?) {
        if (!hasCapability(V2PluginCapabilities.GAME_EVENTS)) {
            return
        }
        eventsService.publish(
            GameEndedEvent(
                data?.gameTime ?: 0,
                data?.mapName.orEmpty(),
                data?.replayName.orEmpty(),
                data?.allPlayerList?.toList().orEmpty(),
                data?.winPlayerList?.toList().orEmpty()
            )
        )
    }

    private fun requireCapability(capability: String) {
        requireActive()
        check(hasCapability(capability)) {
            "Plugin '${descriptor.internalName}' must declare capability '$capability' in plugin.json"
        }
    }

    private fun requireActive() {
        check(isActive()) { "Plugin '${descriptor.internalName}' is no longer active" }
    }

    private fun snapshot(player: PlayerHess): PlayerSnapshot {
        return PlayerSnapshot(
            player.connectHexID,
            player.name,
            player.position,
            player.team,
            player.con != null,
            player.survive,
            StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player),
            StartModeRoomPermissionSupport.hasEffectiveHostAuthority(player),
            player.superAdmin
        )
    }

    private inner class ServerService: PluginServer {
        override fun productName(): String = Data.SERVER_PRODUCT_NAME
        override fun apiVersion(): String = Data.SERVER_API_VERSION
        override fun gameStarted(): Boolean = HeadlessModuleManage.initHPS() && HeadlessModuleManage.hps.room.isStartGame
        override fun players(): List<PlayerSnapshot> {
            if (!HeadlessModuleManage.initHPS()) {
                return emptyList()
            }
            return Collections.unmodifiableList(
                HeadlessModuleManage.hps.room.playerManage.playerGroup.toList().map(::snapshot)
            )
        }

        override fun roomSnapshot(): RoomSnapshot {
            requireCapability(V2PluginCapabilities.READ_STATE)
            return stateSnapshotProvider.roomSnapshot()
        }

        override fun mapSnapshot(): MapSnapshot {
            requireCapability(V2PluginCapabilities.READ_STATE)
            return stateSnapshotProvider.mapSnapshot()
        }

        override fun modSnapshots(): List<ModSnapshot> {
            requireCapability(V2PluginCapabilities.READ_STATE)
            return stateSnapshotProvider.modSnapshots()
        }

        override fun configSnapshot(): ServerConfigSnapshot {
            requireCapability(V2PluginCapabilities.READ_STATE)
            return stateSnapshotProvider.configSnapshot()
        }

        override fun sendSystemMessage(playerId: String, message: String): Boolean {
            requireCapability(V2PluginCapabilities.BROADCAST)
            require(playerId.isNotBlank()) { "Player ID cannot be blank" }
            require(message.isNotBlank()) { "System message cannot be blank" }
            if (!HeadlessModuleManage.initHPS()) {
                return false
            }
            val player = HeadlessModuleManage.hps.room.playerManage.playerGroup.toList()
                .firstOrNull { it.connectHexID == playerId }
                ?: return false
            player.sendSystemMessage(message.take(2_000))
            return true
        }

        override fun broadcastSystemMessage(message: String) {
            requireCapability(V2PluginCapabilities.BROADCAST)
            require(message.isNotBlank()) { "Broadcast message cannot be blank" }
            if (!HeadlessModuleManage.initHPS()) {
                return
            }
            HeadlessModuleManage.hps.room.playerManage.playerGroup.toList().forEach { it.sendSystemMessage(message.take(2_000)) }
        }
    }

    private class LoggerService(
        private val pluginId: String,
        private val sink: PluginLogSink,
        private val metrics: PluginRuntimeMetrics
    ): PluginLogger {
        override fun info(message: String) = sink.info(pluginId, message, metrics)
        override fun warn(message: String) = sink.warn(pluginId, message, metrics)
        override fun error(message: String) = sink.error(pluginId, message, null, metrics)
        override fun error(message: String, error: Throwable) = sink.error(pluginId, message, error, metrics)
    }

    private class CommandsService(
        private val context: V2PluginContext,
        private val scope: PluginResourceScope
    ): PluginCommands {
        private class Definition(
            val name: String,
            val parameters: String,
            val description: String,
            val executor: CommandExecutor
        ) {
            @Volatile var installedHandler: CommandHandler? = null
            @Volatile var installedCommand: CommandHandler.Command? = null
        }

        private val dispatcher = PluginCommandDispatcher(context.pluginId()).also(scope::manage)

        private val consoleDefinitions = CopyOnWriteArrayList<Definition>()
        private val playerDefinitions = CopyOnWriteArrayList<Definition>()
        @Volatile private var consoleHandler: CommandHandler? = null
        @Volatile private var playerHandler: CommandHandler? = null

        override fun registerConsole(
            name: String,
            parameters: String,
            description: String,
            executor: CommandExecutor
        ): Registration {
            context.requireCapability(V2PluginCapabilities.CONSOLE_COMMANDS)
            return register(consoleDefinitions, consoleHandler, Definition(name, parameters, description, executor), false)
        }

        override fun registerPlayer(
            name: String,
            parameters: String,
            description: String,
            executor: CommandExecutor
        ): Registration {
            context.requireCapability(V2PluginCapabilities.PLAYER_COMMANDS)
            return register(playerDefinitions, playerHandler, Definition(name, parameters, description, executor), true)
        }

        fun attachConsole(handler: CommandHandler) {
            consoleHandler = handler
            consoleDefinitions.forEach { install(handler, it, false) }
        }

        fun attachPlayer(handler: CommandHandler) {
            playerHandler = handler
            playerDefinitions.forEach { install(handler, it, true) }
        }

        private fun register(
            definitions: CopyOnWriteArrayList<Definition>,
            handler: CommandHandler?,
            definition: Definition,
            playerCommand: Boolean
        ): Registration {
            require(definition.name.isNotBlank()) { "Command name cannot be blank" }
            require(definitions.none { it.name.equals(definition.name, ignoreCase = true) }) {
                "Plugin command '${definition.name}' is already registered"
            }
            // Parse command metadata before retaining the definition.
            CommandHandler.Command(definition.name, definition.parameters, definition.description, CommandHandler.CommandRunner<Any?> { _, _ -> })
            definitions.add(definition)
            try {
                handler?.let { install(it, definition, playerCommand) }
                return scope.registration {
                    definitions.remove(definition)
                    uninstall(definition)
                }
            } catch (error: Throwable) {
                definitions.remove(definition)
                uninstall(definition)
                throw error
            }
        }

        private fun uninstall(definition: Definition) {
            val installedHandler = definition.installedHandler
            val installedCommand = definition.installedCommand
            if (installedHandler != null && installedCommand != null) {
                installedHandler.removeCommand(definition.name, installedCommand)
            }
            definition.installedHandler = null
            definition.installedCommand = null
        }

        private fun install(handler: CommandHandler, definition: Definition, playerCommand: Boolean) {
            check(!handler.containsCommand(definition.name)) {
                "Plugin command '${definition.name}' conflicts with an existing command"
            }
            val command = if (playerCommand) {
                handler.register(
                    definition.name,
                    definition.parameters,
                    definition.description,
                    CommandHandler.CommandRunner<PlayerHess> { args, player ->
                        invoke(definition, args, player, StrCons { message -> player.sendSystemMessage(message) })
                    }
                )
            } else {
                handler.register(
                    definition.name,
                    definition.parameters,
                    definition.description,
                    CommandHandler.CommandRunner<StrCons> { args, output -> invoke(definition, args, null, output) }
                )
            }
            definition.installedHandler = handler
            definition.installedCommand = command
        }

        private fun invoke(definition: Definition, args: Array<String>, player: PlayerHess?, output: StrCons) {
            if (!context.isActive()) {
                output("Plugin '${context.pluginId()}' is not active")
                return
            }
            val allowReply = AtomicBoolean(true)
            val reply = StrCons { if (allowReply.get()) output(it) }
            val future = dispatcher.submit(Runnable { execute(definition, args.copyOf(), player, reply) })
            if (future == null) {
                output("Plugin command queue is full or stopped; please retry.")
                return
            }
            if (player == null) {
                try { future.get(2, TimeUnit.SECONDS) }
                catch (error: Exception) {
                    allowReply.set(false)
                    future.cancel(true)
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    output("Plugin command timed out after 2 seconds. Completion is unconfirmed; check plugin logs before retrying.")
                }
            }
        }

        private fun execute(definition: Definition, args: Array<String>, player: PlayerHess?, output: StrCons) {
            if (!context.isActive() || definition.installedCommand == null) return

            try {
                V2PluginExecution.withContextClassLoader(context.pluginClassLoader) {
                    definition.executor.execute(object: CommandInvocation {
                        override fun arguments(): List<String> = Collections.unmodifiableList(args.toList())
                        override fun player(): Optional<PlayerSnapshot> = Optional.ofNullable(player?.let(context::snapshot))
                        override fun reply(message: String) = output(message.take(2_000))
                    })
                }
                context.runtimeMetrics.recordCommandSuccess()
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) {
                    throw error
                }
                context.runtimeMetrics.recordCommandFailure()
                context.logger().error("Command '${definition.name}' failed", error)
                output("Plugin command failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private class EventsService(
        private val context: V2PluginContext,
        private val scope: PluginResourceScope
    ): PluginEvents {
        private val listeners = java.util.concurrent.ConcurrentHashMap<Class<out PluginEvent>, CopyOnWriteArrayList<PluginEventListener<out PluginEvent>>>()
        private val dispatcher = PluginEventDispatcher(
            context.pluginId(),
            context.runtimeMetrics,
            context.pluginLogSink
        ).also(scope::manage)

        fun stop() {
            dispatcher.close()
        }

        override fun <T: PluginEvent> subscribe(eventType: Class<T>, listener: PluginEventListener<T>): Registration {
            when (eventType) {
                PlayerJoinedEvent::class.java, PlayerLeftEvent::class.java, PlayerChatEvent::class.java ->
                    context.requireCapability(V2PluginCapabilities.PLAYER_EVENTS)
                GameStartedEvent::class.java, GameEndedEvent::class.java ->
                    context.requireCapability(V2PluginCapabilities.GAME_EVENTS)
                else -> throw IllegalArgumentException("Unsupported event type: ${eventType.name}")
            }
            val eventListeners = listeners.computeIfAbsent(eventType) { CopyOnWriteArrayList() }
            eventListeners.add(listener)
            return scope.registration { eventListeners.remove(listener) }
        }

        @Suppress("UNCHECKED_CAST")
        fun <T: PluginEvent> publish(event: T) {
            if (!context.isActive()) {
                return
            }
            val snapshot = listeners[event.javaClass]?.toList().orEmpty()
            if (snapshot.isEmpty()) {
                return
            }
            dispatcher.dispatch {
                snapshot.forEach { rawListener ->
                    if (!context.isActive()) {
                        return@forEach
                    }
                    val startedAt = System.nanoTime()
                    try {
                        V2PluginExecution.withContextClassLoader(context.pluginClassLoader) {
                            (rawListener as PluginEventListener<T>).onEvent(event)
                        }
                        val slowWarning = context.runtimeMetrics.recordEventCallbackSuccess(System.nanoTime() - startedAt)
                        if (slowWarning) {
                            context.logger().warn(
                                "Slow event callback event=${event.javaClass.simpleName} " +
                                    "durationUs=${context.runtimeMetrics.lastEventCallbackDurationMicros()} " +
                                    "thresholdMs=${PluginRuntimeMetrics.SLOW_EVENT_CALLBACK_MILLIS}"
                            )
                        }
                    } catch (error: Throwable) {
                        if (error is VirtualMachineError || error is ThreadDeath) {
                            throw error
                        }
                        if (!context.isActive() && error is InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@forEach
                        }
                        context.runtimeMetrics.recordEventCallbackFailure(System.nanoTime() - startedAt)
                        context.logger().error("Event listener failed for ${event.javaClass.simpleName}", error)
                    }
                }
            }
        }
    }

    private class SchedulerService(
        private val pluginId: String,
        private val scope: PluginResourceScope,
        private val pluginClassLoader: ClassLoader,
        private val runtimeMetrics: PluginRuntimeMetrics,
        private val pluginLogSink: PluginLogSink
    ): PluginScheduler {
        private val sequence = AtomicInteger()

        override fun schedule(taskId: String, delayMillis: Long, task: Runnable): Registration {
            return scheduleInternal(taskId, delayMillis, null, task)
        }

        override fun scheduleRepeating(
            taskId: String,
            initialDelayMillis: Long,
            intervalMillis: Long,
            task: Runnable
        ): Registration {
            require(intervalMillis >= 100L) { "Repeating plugin task interval must be at least 100 ms" }
            return scheduleInternal(taskId, initialDelayMillis, intervalMillis, task)
        }

        private fun scheduleInternal(taskId: String, delayMillis: Long, intervalMillis: Long?, task: Runnable): Registration {
            require(taskId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"))) { "Invalid taskId '$taskId'" }
            require(delayMillis >= 0L) { "Task delay cannot be negative" }
            val taskName = "$pluginId.$taskId.${sequence.incrementAndGet()}"
            val taskGroup = "PluginV2.$pluginId"
            val guarded = Runnable {
                if (!scope.isActive()) {
                    return@Runnable
                }
                try {
                    V2PluginExecution.withContextClassLoader(pluginClassLoader) {
                        task.run()
                    }
                    runtimeMetrics.recordSchedulerSuccess()
                } catch (error: Throwable) {
                    if (error is VirtualMachineError || error is ThreadDeath) {
                        throw error
                    }
                    runtimeMetrics.recordSchedulerFailure()
                    pluginLogSink.error(pluginId, "Scheduled task '$taskId' failed", error, runtimeMetrics)
                }
            }
            if (intervalMillis == null) {
                Threads.newCountdown(taskName, taskGroup, "V2 plugin task", delayMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), TimeUnit.MILLISECONDS, guarded)
            } else {
                Threads.newTimedTask(
                    taskName,
                    taskGroup,
                    "V2 plugin repeating task",
                    delayMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    intervalMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    TimeUnit.MILLISECONDS,
                    guarded
                )
            }
            return scope.registration { Threads.closeTimeTask(taskName, taskGroup) }
        }
    }
}
