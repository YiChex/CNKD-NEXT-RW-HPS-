package net.rwhps.server.plugin.api.event;

public final class GameStartedEvent implements PluginEvent {
    private final long occurredAtMillis;

    public GameStartedEvent(long occurredAtMillis) {
        this.occurredAtMillis = occurredAtMillis;
    }

    public long occurredAtMillis() { return occurredAtMillis; }
}
