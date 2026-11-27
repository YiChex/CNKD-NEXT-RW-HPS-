package net.rwhps.server.plugin.api.event;

@FunctionalInterface
public interface PluginEventListener<T extends PluginEvent> {
    void onEvent(T event) throws Exception;
}
