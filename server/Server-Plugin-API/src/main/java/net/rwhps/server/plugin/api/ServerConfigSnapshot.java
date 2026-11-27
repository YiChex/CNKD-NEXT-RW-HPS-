package net.rwhps.server.plugin.api;

import java.util.Objects;

/**
 * Immutable allowlisted server configuration. Passwords, tokens, license data,
 * network targets and filesystem paths are intentionally excluded.
 */
public final class ServerConfigSnapshot {
    private final String serverName;
    private final int maxPlayers;
    private final int maxUnits;
    private final int maxMessageLength;
    private final boolean aiEnabled;
    private final boolean keepAtLeastOneHost;
    private final int autoStartMinPlayers;
    private final int autoStartCountdownSeconds;
    private final boolean teamChangeLocked;
    private final boolean positionChangeLocked;
    private final boolean mapLockEnabled;
    private final boolean lowLatencyEnabled;
    private final int defaultStartingCredits;
    private final boolean noNukesDefault;
    private final String defaultFogMode;

    public ServerConfigSnapshot(
        String serverName,
        int maxPlayers,
        int maxUnits,
        int maxMessageLength,
        boolean aiEnabled,
        boolean keepAtLeastOneHost,
        int autoStartMinPlayers,
        int autoStartCountdownSeconds,
        boolean teamChangeLocked,
        boolean positionChangeLocked,
        boolean mapLockEnabled,
        boolean lowLatencyEnabled,
        int defaultStartingCredits,
        boolean noNukesDefault,
        String defaultFogMode
    ) {
        this.serverName = Objects.requireNonNull(serverName, "serverName");
        this.maxPlayers = nonNegative(maxPlayers, "maxPlayers");
        this.maxUnits = nonNegative(maxUnits, "maxUnits");
        this.maxMessageLength = nonNegative(maxMessageLength, "maxMessageLength");
        this.aiEnabled = aiEnabled;
        this.keepAtLeastOneHost = keepAtLeastOneHost;
        this.autoStartMinPlayers = autoStartMinPlayers;
        this.autoStartCountdownSeconds = nonNegative(autoStartCountdownSeconds, "autoStartCountdownSeconds");
        this.teamChangeLocked = teamChangeLocked;
        this.positionChangeLocked = positionChangeLocked;
        this.mapLockEnabled = mapLockEnabled;
        this.lowLatencyEnabled = lowLatencyEnabled;
        this.defaultStartingCredits = nonNegative(defaultStartingCredits, "defaultStartingCredits");
        this.noNukesDefault = noNukesDefault;
        this.defaultFogMode = Objects.requireNonNull(defaultFogMode, "defaultFogMode");
    }

    public String serverName() { return serverName; }
    public int maxPlayers() { return maxPlayers; }
    public int maxUnits() { return maxUnits; }
    public int maxMessageLength() { return maxMessageLength; }
    public boolean aiEnabled() { return aiEnabled; }
    public boolean keepAtLeastOneHost() { return keepAtLeastOneHost; }
    public int autoStartMinPlayers() { return autoStartMinPlayers; }
    public int autoStartCountdownSeconds() { return autoStartCountdownSeconds; }
    public boolean teamChangeLocked() { return teamChangeLocked; }
    public boolean positionChangeLocked() { return positionChangeLocked; }
    public boolean mapLockEnabled() { return mapLockEnabled; }
    public boolean lowLatencyEnabled() { return lowLatencyEnabled; }
    public int defaultStartingCredits() { return defaultStartingCredits; }
    public boolean noNukesDefault() { return noNukesDefault; }
    public String defaultFogMode() { return defaultFogMode; }

    private static int nonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
        return value;
    }
}
