package net.rwhps.server.plugin.api;

import net.rwhps.server.plugin.api.command.CommandExecutor;

public interface PluginCommands {
    /** Registers a console command. Requires {@code console-commands}. */
    Registration registerConsole(String name, String parameters, String description, CommandExecutor executor);

    /** Registers a room-player command. Requires {@code player-commands}. */
    Registration registerPlayer(String name, String parameters, String description, CommandExecutor executor);
}
