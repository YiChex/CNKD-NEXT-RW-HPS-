package net.rwhps.server.plugin.api.event;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class GameEndedEvent implements PluginEvent {
    private final int gameTime;
    private final String mapName;
    private final String replayName;
    private final List<String> players;
    private final List<String> winners;

    public GameEndedEvent(
        int gameTime,
        String mapName,
        String replayName,
        List<String> players,
        List<String> winners
    ) {
        this.gameTime = gameTime;
        this.mapName = Objects.requireNonNull(mapName, "mapName");
        this.replayName = Objects.requireNonNull(replayName, "replayName");
        this.players = Collections.unmodifiableList(List.copyOf(players));
        this.winners = Collections.unmodifiableList(List.copyOf(winners));
    }

    public int gameTime() { return gameTime; }
    public String mapName() { return mapName; }
    public String replayName() { return replayName; }
    public List<String> players() { return players; }
    public List<String> winners() { return winners; }
}
