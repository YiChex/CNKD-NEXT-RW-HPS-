package net.rwhps.server.plugin

import java.util.Locale
import java.util.Collections

internal object V2PluginCapabilities {
    const val CONSOLE_COMMANDS = "console-commands"
    const val PLAYER_COMMANDS = "player-commands"
    const val PLAYER_EVENTS = "player-events"
    const val GAME_EVENTS = "game-events"
    const val BROADCAST = "broadcast"
    const val SCHEDULER = "scheduler"
    const val READ_STATE = "read-state"
    const val READ_STATISTICS = "read-statistics"

    val ALL = setOf(CONSOLE_COMMANDS, PLAYER_COMMANDS, PLAYER_EVENTS, GAME_EVENTS, BROADCAST, SCHEDULER, READ_STATE, READ_STATISTICS)

    fun normalize(capabilities: Collection<String>): Set<String> {
        val normalized = capabilities.mapTo(linkedSetOf()) { it.trim().lowercase(Locale.ROOT) }
        return Collections.unmodifiableSet(normalized)
    }
}
