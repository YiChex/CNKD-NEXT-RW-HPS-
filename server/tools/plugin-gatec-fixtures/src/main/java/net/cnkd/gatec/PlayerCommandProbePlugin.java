package net.cnkd.gatec;

import net.rwhps.server.plugin.api.CnkdPlugin;
import net.rwhps.server.plugin.api.PluginContext;

public final class PlayerCommandProbePlugin implements CnkdPlugin {
    @Override
    public void onEnable(PluginContext context) {
        context.commands().registerPlayer(
            "gatecplayerprobe",
            "",
            "Gate C player command capability probe",
            invocation -> invocation.reply("gatec-player-ready")
        );
    }
}
