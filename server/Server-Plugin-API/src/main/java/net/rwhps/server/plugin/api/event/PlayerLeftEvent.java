package net.rwhps.server.plugin.api.event;

import net.rwhps.server.plugin.api.PlayerSnapshot;

import java.util.Objects;

public final class PlayerLeftEvent implements PluginEvent {
    private final PlayerSnapshot player;

    public PlayerLeftEvent(PlayerSnapshot player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    public PlayerSnapshot player() { return player; }
}
