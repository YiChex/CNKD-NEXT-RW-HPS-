package net.cnkd.gatec;

import net.rwhps.server.plugin.api.CnkdPlugin;
import net.rwhps.server.plugin.api.PluginContext;

public final class SchedulerProbePlugin implements CnkdPlugin {
    @Override
    public void onEnable(PluginContext context) {
        context.scheduler().scheduleRepeating(
            "gatec-scheduler-probe",
            1_000L,
            30_000L,
            () -> {
                // The server-owned callback counter proves this no-op executed during Gate C.
            }
        );
    }
}
