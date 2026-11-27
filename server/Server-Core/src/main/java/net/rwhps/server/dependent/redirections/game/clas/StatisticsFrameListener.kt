package net.rwhps.server.dependent.redirections.game.clas
import net.rwhps.asm.api.listener.RedirectionListener
import net.rwhps.server.analytics.StatisticsBridge
import net.rwhps.server.plugin.internal.headless.inject.core.GameEngine

/** Executed after an actual simulation update in the native game loader. */
object StatisticsFrameListener: RedirectionListener {
    override fun invoke(obj: Any, desc: String, vararg args: Any?) {
        val module=GameEngine.statisticsModuleOrNull?:return
        StatisticsBridge.capture(module)
    }
}
