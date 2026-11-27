package net.rwhps.server.plugin.api;

/** Stable high-level single-room lifecycle state. */
public enum RoomPhase {
    STARTING,
    LOBBY,
    PLAYING,
    RETURNING,
    RESETTING
}
