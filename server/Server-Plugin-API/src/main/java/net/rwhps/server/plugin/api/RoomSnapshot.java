package net.rwhps.server.plugin.api;

import java.util.Objects;

/** Immutable high-level room state without mutable Headless objects. */
public final class RoomSnapshot {
    private final boolean available;
    private final RoomPhase phase;
    private final boolean gameStarted;
    private final boolean resetInProgress;
    private final boolean battleRoomReturnInProgress;
    private final int occupantCount;
    private final int connectedHumanCount;
    private final int aiPlayerCount;
    private final int spectatorCount;
    private final int maxPlayers;

    public RoomSnapshot(
        boolean available,
        RoomPhase phase,
        boolean gameStarted,
        boolean resetInProgress,
        boolean battleRoomReturnInProgress,
        int occupantCount,
        int connectedHumanCount,
        int aiPlayerCount,
        int spectatorCount,
        int maxPlayers
    ) {
        this.available = available;
        this.phase = Objects.requireNonNull(phase, "phase");
        this.gameStarted = gameStarted;
        this.resetInProgress = resetInProgress;
        this.battleRoomReturnInProgress = battleRoomReturnInProgress;
        this.occupantCount = nonNegative(occupantCount, "occupantCount");
        this.connectedHumanCount = nonNegative(connectedHumanCount, "connectedHumanCount");
        this.aiPlayerCount = nonNegative(aiPlayerCount, "aiPlayerCount");
        this.spectatorCount = nonNegative(spectatorCount, "spectatorCount");
        this.maxPlayers = nonNegative(maxPlayers, "maxPlayers");
    }

    public boolean available() { return available; }
    public RoomPhase phase() { return phase; }
    public boolean gameStarted() { return gameStarted; }
    public boolean resetInProgress() { return resetInProgress; }
    public boolean battleRoomReturnInProgress() { return battleRoomReturnInProgress; }
    public int occupantCount() { return occupantCount; }
    public int connectedHumanCount() { return connectedHumanCount; }
    public int aiPlayerCount() { return aiPlayerCount; }
    public int spectatorCount() { return spectatorCount; }
    public int maxPlayers() { return maxPlayers; }

    private static int nonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
        return value;
    }
}
