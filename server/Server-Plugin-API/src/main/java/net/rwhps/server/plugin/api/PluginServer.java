package net.rwhps.server.plugin.api;

import java.util.List;

/** Safe single-room server facade containing snapshots and controlled messages. */
public interface PluginServer {
    String productName();

    String apiVersion();

    boolean gameStarted();

    /** Returns an immutable snapshot list. */
    List<PlayerSnapshot> players();

    /** Returns a high-level immutable room snapshot. Requires {@code read-state}. */
    default RoomSnapshot roomSnapshot() {
        throw new UnsupportedOperationException("roomSnapshot requires CNKD Plugin API 1.2.0 or newer");
    }

    /** Returns the currently selected map without source filesystem paths. Requires {@code read-state}. */
    default MapSnapshot mapSnapshot() {
        throw new UnsupportedOperationException("mapSnapshot requires CNKD Plugin API 1.2.0 or newer");
    }

    /** Returns immutable engine-loaded mod identities. Requires {@code read-state}. */
    default List<ModSnapshot> modSnapshots() {
        throw new UnsupportedOperationException("modSnapshots requires CNKD Plugin API 1.2.0 or newer");
    }

    /** Returns a non-sensitive allowlisted configuration snapshot. Requires {@code read-state}. */
    default ServerConfigSnapshot configSnapshot() {
        throw new UnsupportedOperationException("configSnapshot requires CNKD Plugin API 1.2.0 or newer");
    }

    /**
     * Sends at most 2000 characters to the currently connected player ID.
     * Requires the {@code broadcast} capability and returns false if absent.
     */
    boolean sendSystemMessage(String playerId, String message);

    /** Sends at most 2000 characters to every current room player. */
    void broadcastSystemMessage(String message);
}
