package net.rwhps.server.plugin.api;

import net.rwhps.server.plugin.api.event.PluginEvent;
import net.rwhps.server.plugin.api.event.PluginEventListener;

public interface PluginEvents {
    /**
     * Subscribes to an immutable notification event. Delivery is asynchronous
     * and ordered within this plugin's bounded queue. Notifications may be
     * dropped under queue pressure and never intercept game commands.
     */
    <T extends PluginEvent> Registration subscribe(Class<T> eventType, PluginEventListener<T> listener);
}
