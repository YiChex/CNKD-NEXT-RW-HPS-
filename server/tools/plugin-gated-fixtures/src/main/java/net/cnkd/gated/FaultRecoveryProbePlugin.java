package net.cnkd.gated;

import java.util.concurrent.atomic.AtomicBoolean;
import net.rwhps.server.plugin.api.CnkdPlugin;
import net.rwhps.server.plugin.api.PluginContext;
import net.rwhps.server.plugin.api.event.GameStartedEvent;

/**
 * Gate D fixture: deliberately fails once in each callback category while
 * leaving all game state untouched. The next GameStartedEvent must succeed.
 */
public final class FaultRecoveryProbePlugin implements CnkdPlugin {
    private final AtomicBoolean firstGameStart = new AtomicBoolean(true);

    @Override
    public void onEnable(PluginContext context) {
        context.commands().registerPlayer(
            "gatecfaultprobe",
            "",
            "Gate D controlled command failure probe",
            invocation -> {
                throw new IllegalStateException("gate-d expected player command failure");
            }
        );
        context.events().subscribe(GameStartedEvent.class, event -> {
            if (firstGameStart.compareAndSet(true, false)) {
                throw new IllegalStateException("gate-d expected first game-start listener failure");
            }
        });
    }
}
