package net.rwhps.server.plugin.api;
import java.util.List;
import java.util.Map;

/** One exclusive restart-scoped collector. Acknowledge only after durable private journal commit. */
public interface PluginStatistics extends AutoCloseable {
    /** Disable ends any active collection as partial. Re-enable applies to the next match. */
    void enabled(boolean value);
    /** Non-blocking immutable batch; terminal/session records survive frame pressure. */
    List<StatisticsRecord> read(int maximumRecords);
    /** Idempotent ordered acknowledgment; records past the last read cannot be acknowledged. */
    void acknowledge(long sequence);
    Map<String,Object> status();
    @Override void close();
}
