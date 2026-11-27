package net.rwhps.server.plugin.api;

public interface PluginLogger {
    void info(String message);

    void warn(String message);

    void error(String message);

    void error(String message, Throwable error);
}
