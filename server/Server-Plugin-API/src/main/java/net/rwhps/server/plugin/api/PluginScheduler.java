package net.rwhps.server.plugin.api;

public interface PluginScheduler {
    /** Schedules one owned task after a non-negative delay. */
    Registration schedule(String taskId, long delayMillis, Runnable task);

    /** Schedules an owned task with an interval of at least 100 milliseconds. */
    Registration scheduleRepeating(String taskId, long initialDelayMillis, long intervalMillis, Runnable task);
}
