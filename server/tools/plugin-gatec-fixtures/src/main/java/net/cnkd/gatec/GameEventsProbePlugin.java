package net.cnkd.gatec;

import net.rwhps.server.plugin.api.CnkdPlugin;
import net.rwhps.server.plugin.api.PluginContext;
import net.rwhps.server.plugin.api.event.GameStartedEvent;

public final class GameEventsProbePlugin implements CnkdPlugin {
    @Override
    public void onEnable(PluginContext context) {
        context.events().subscribe(GameStartedEvent.class, event -> {
            // A no-op notification listener exercises the asynchronous game-event bridge.
        });
    }
}
