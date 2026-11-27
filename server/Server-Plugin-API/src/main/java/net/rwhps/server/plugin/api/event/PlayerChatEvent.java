package net.rwhps.server.plugin.api.event;

import net.rwhps.server.plugin.api.PlayerSnapshot;

import java.util.Objects;

public final class PlayerChatEvent implements PluginEvent {
    private final PlayerSnapshot player;
    private final String message;

    public PlayerChatEvent(PlayerSnapshot player, String message) {
        this.player = Objects.requireNonNull(player, "player");
        this.message = Objects.requireNonNull(message, "message");
    }

    public PlayerSnapshot player() { return player; }
    public String message() { return message; }
}
