package net.rwhps.server.plugin.api;

import java.nio.file.Path;
import java.util.Set;

/**
 * Services and metadata owned by one V2 plugin instance. All returned service
 * objects are scoped to that instance and must not be shared with other plugins.
 */
public interface PluginContext {
    String pluginId();

    String pluginName();

    String pluginVersion();

    Set<String> declaredCapabilities();

    /** Stable private data directory for this plugin ID. */
    Path dataDirectory();

    PluginLogger logger();

    PluginServer server();

    PluginCommands commands();

    PluginEvents events();

    PluginScheduler scheduler();

    /** Exclusive bounded statistics stream. Requires read-statistics; added in API 1.3.0. */
    default PluginStatistics statistics() {
        throw new UnsupportedOperationException("statistics requires CNKD Plugin API 1.3.0");
    }

    /**
     * Adds a plugin-owned resource to reverse-order shutdown. If shutdown has
     * already started, the resource is closed immediately and the call fails.
     */
    void manage(AutoCloseable resource);
}
