package example.cnkd;

import net.rwhps.server.plugin.api.CnkdPlugin;
import net.rwhps.server.plugin.api.PluginContext;
import net.rwhps.server.plugin.api.event.GameStartedEvent;
import net.rwhps.server.plugin.api.event.PlayerJoinedEvent;

public final class WelcomePlugin implements CnkdPlugin {
    private PluginContext context;

    @Override
    public void onEnable(PluginContext context) {
        this.context = context;

        context.commands().registerPlayer(
            "hello",
            "[name]",
            "Send a greeting",
            invocation -> {
                String name = invocation.arguments().isEmpty()
                    ? invocation.player().map(player -> player.name()).orElse("player")
                    : invocation.arguments().get(0);
                invocation.reply("Hello, " + name + "!");
            }
        );

        context.events().subscribe(PlayerJoinedEvent.class, event ->
            context.logger().info("Player joined: " + event.player().name())
        );
        context.events().subscribe(GameStartedEvent.class, event ->
            context.server().broadcastSystemMessage("The match has started.")
        );

        context.scheduler().scheduleRepeating(
            "status",
            30_000L,
            60_000L,
            () -> context.logger().info("Online players: " + context.server().players().size())
        );
    }

    @Override
    public void onReady() {
        context.logger().info(
            "CNKD Welcome is ready on API " + context.server().apiVersion()
                + " room=" + context.server().roomSnapshot().phase()
                + " map=" + context.server().mapSnapshot().displayName()
        );
    }

    @Override
    public void onDisable() {
        context.logger().info("CNKD Welcome is stopping");
    }
}
