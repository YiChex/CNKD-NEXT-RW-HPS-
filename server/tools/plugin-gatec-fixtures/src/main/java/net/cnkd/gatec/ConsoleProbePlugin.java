package net.cnkd.gatec;

import net.rwhps.server.plugin.api.CnkdPlugin;
import net.rwhps.server.plugin.api.PluginContext;

public final class ConsoleProbePlugin implements CnkdPlugin {
    @Override
    public void onEnable(PluginContext context) {
        context.commands().registerConsole(
            "gatecconsoleprobe",
            "",
            "Gate C console capability probe",
            invocation -> invocation.reply("gatec-console-ready")
        );
    }
}
