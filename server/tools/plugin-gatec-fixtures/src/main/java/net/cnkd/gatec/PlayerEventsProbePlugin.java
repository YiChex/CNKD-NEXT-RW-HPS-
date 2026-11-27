package net.cnkd.gatec;

import net.rwhps.server.plugin.api.CnkdPlugin;
import net.rwhps.server.plugin.api.PluginContext;
import net.rwhps.server.plugin.api.event.PlayerJoinedEvent;

public final class PlayerEventsProbePlugin implements CnkdPlugin {
    @Override
    public void onEnable(PluginContext context) {
        context.events().subscribe(PlayerJoinedEvent.class, event -> {
            // A no-op notification listener exercises the asynchronous player-event bridge.
        });
    }
}
