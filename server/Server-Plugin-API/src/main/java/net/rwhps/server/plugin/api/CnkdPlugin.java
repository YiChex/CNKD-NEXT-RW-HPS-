package net.rwhps.server.plugin.api;

/**
 * Stable V2 entry point for trusted, restart-scoped CNKD NEXT plugins.
 * Implementations must provide a public no-argument constructor and must not
 * start threads or acquire resources from that constructor.
 */
public interface CnkdPlugin {
    /** Registers capabilities and owned resources. */
    void onEnable(PluginContext context) throws Exception;

    /** Runs after the server has completed all plugin registration phases. */
    default void onReady() throws Exception {
    }

    /**
     * Runs at most once during normal shutdown or startup rollback. New
     * registrations are rejected before this callback; owned resources are
     * closed in reverse order after it returns.
     */
    default void onDisable() throws Exception {
    }
}
