package net.rwhps.server.plugin.v2

import net.rwhps.server.game.event.EventManage
import net.rwhps.server.game.event.core.EventListenerHost
import net.rwhps.server.game.event.game.PlayerChatEvent as CorePlayerChatEvent
import net.rwhps.server.game.event.game.PlayerJoinEvent as CorePlayerJoinEvent
import net.rwhps.server.game.event.game.PlayerLeaveEvent as CorePlayerLeaveEvent
import net.rwhps.server.game.event.game.ServerGameOverEvent as CoreGameOverEvent
import net.rwhps.server.game.event.game.ServerGameStartEvent as CoreGameStartEvent
import net.rwhps.server.plugin.Plugin
import net.rwhps.server.plugin.V2PluginCapabilities
import net.rwhps.server.util.annotations.core.EventListenerHandler
import net.rwhps.server.util.game.command.CommandHandler
import java.util.concurrent.atomic.AtomicBoolean

internal class V2PluginAdapter(private val runtime: V2PluginRuntime): Plugin() {
    private val disableStarted = AtomicBoolean(false)

    override fun onEnable() {
        runStartupPhase { runtime.plugin.onEnable(runtime.context) }
    }

    override fun registerCoreCommands(handler: CommandHandler) {
        if (runtime.context.hasCapability(V2PluginCapabilities.CONSOLE_COMMANDS)) {
            runStartupPhase { runtime.context.attachConsoleCommands(handler) }
        }
    }

    override fun registerServerClientCommands(handler: CommandHandler) {
        if (runtime.context.hasCapability(V2PluginCapabilities.PLAYER_COMMANDS)) {
            runStartupPhase { runtime.context.attachPlayerCommands(handler) }
        }
    }

    override fun registerEvents(eventManage: EventManage) {
        if (runtime.context.hasCapability(V2PluginCapabilities.PLAYER_EVENTS) ||
            runtime.context.hasCapability(V2PluginCapabilities.GAME_EVENTS)
        ) {
            runStartupPhase {
                runtime.context.manage(eventManage.registerListenerScoped(BridgeEvents(runtime.context)))
            }
        }
    }

    override fun init() {
        runStartupPhase { runtime.plugin.onReady() }
    }

    override fun onDisable() {
        shutdown()
    }

    private fun runStartupPhase(action: () -> Unit) {
        try {
            V2PluginExecution.withContextClassLoader(runtime.classLoader, action)
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) {
                throw error
            }
            try {
                shutdown()
            } catch (disableError: Throwable) {
                error.addSuppressed(disableError)
            }
            throw error
        }
    }

    private fun shutdown() {
        if (!disableStarted.compareAndSet(false, true)) {
            return
        }
        runtime.context.deactivate()
        try {
            V2PluginExecution.withContextClassLoader(runtime.classLoader) {
                runtime.plugin.onDisable()
            }
        } finally {
            runtime.scope.close()
        }
    }

    private class BridgeEvents(private val context: V2PluginContext): EventListenerHost {
        @EventListenerHandler
        fun onPlayerJoin(event: CorePlayerJoinEvent) {
            context.publishPlayerJoined(event.player)
        }

        @EventListenerHandler
        fun onPlayerLeave(event: CorePlayerLeaveEvent) {
            context.publishPlayerLeft(event.player)
        }

        @EventListenerHandler
        fun onPlayerChat(event: CorePlayerChatEvent) {
            context.publishPlayerChat(event.player, event.message)
        }

        @EventListenerHandler
        fun onGameStart(event: CoreGameStartEvent) {
            context.publishGameStarted()
        }

        @EventListenerHandler
        fun onGameOver(event: CoreGameOverEvent) {
            context.publishGameEnded(event.gameOverData)
        }
    }
}
