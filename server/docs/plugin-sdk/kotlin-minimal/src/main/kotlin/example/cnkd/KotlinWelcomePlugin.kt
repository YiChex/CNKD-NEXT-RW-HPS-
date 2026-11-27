package example.cnkd

import net.rwhps.server.plugin.api.CnkdPlugin
import net.rwhps.server.plugin.api.PluginContext
import net.rwhps.server.plugin.api.event.PlayerJoinedEvent

class KotlinWelcomePlugin: CnkdPlugin {
    private lateinit var context: PluginContext

    override fun onEnable(context: PluginContext) {
        this.context = context
        context.commands().registerPlayer("khello", "", "Send a greeting") { invocation ->
            invocation.reply("Hello from Kotlin")
        }
        context.events().subscribe(PlayerJoinedEvent::class.java) { event ->
            context.logger().info("Player joined: ${event.player().name()}")
        }
    }

    override fun onReady() {
        context.logger().info(
            "Kotlin Welcome is ready room=${context.server().roomSnapshot().phase()} " +
                "map=${context.server().mapSnapshot().displayName()}"
        )
    }
}
