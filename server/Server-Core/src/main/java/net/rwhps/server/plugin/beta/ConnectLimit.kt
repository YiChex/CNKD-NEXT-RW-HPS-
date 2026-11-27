package net.rwhps.server.plugin.beta

import net.rwhps.server.game.event.EventGlobalManage
import net.rwhps.server.plugin.Plugin
import net.rwhps.server.util.log.Log

/** Compatibility plugin ID. TCP admission is owned solely by ConnectionLifecycleGuard. */
class ConnectLimit: Plugin() {
    override fun registerGlobalEvents(eventManage: EventGlobalManage) {
        Log.clog("[NetGuard] Unified TCP admission enabled; legacy daily IP and permanent /24 auto-bans retired.")
    }
}
