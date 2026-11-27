package net.rwhps.server.plugin.beta.http

import net.rwhps.server.data.global.Data
import net.rwhps.server.game.headless.core.AbstractGameModule
import net.rwhps.server.game.headless.core.link.FullGameSyncLink
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.plugin.beta.http.data.GetData
import net.rwhps.server.plugin.internal.headless.support.StartModeAutoStartSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeGameOptionSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeGameOverReturnSupport

/** Read engine-backed management snapshots on the owning game thread. Failure is unavailable, not zero. */
internal object ManagementStateSupport {
    private fun <T: Any> snapshot(read: (AbstractGameModule) -> T): T {
        check(HeadlessModuleManage.initHPS()) { "headless_not_ready" }
        val module = HeadlessModuleManage.hps
        var value: T? = null
        check(module.gameFunction.suspendMainThreadOperations { value = read(module) }) { "game_thread_snapshot_failed" }
        return checkNotNull(value) { "snapshot_unavailable" }
    }

    fun gameInfo(): GetData.GameInfo = snapshot { module ->
        val settings = module.gameLinkServerData
        GetData.GameInfo(settings.income, StartModeGameOptionSupport.currentNoNukes(),
            StartModeGameOptionSupport.currentDisplayedCreditsAmount(), StartModeGameOptionSupport.currentFogValue(),
            settings.sharedcontrol, module.room.isStartGame, module.room.playerManage.playerAll.map { it.playerInfo })
    }

    fun serverInfo(): Map<String, Any> = snapshot { module ->
        val room = module.room
        mapOf("onlinePlayers" to room.playerManage.countConnectedHumanPlayers(), "totalRooms" to 1,
            "ongoingRooms" to if (room.isStartGame) 1 else 0,
            "notStartedRooms" to if (room.isStartGame) 0 else 1)
    }

    fun worldState(): Map<String, Any> = snapshot { module ->
        val room = module.room
        mapOf("roomStarted" to room.isStartGame,
            "lifecyclePlaying" to StartModeGameOverReturnSupport.isPlaying(),
            "lobbyGeneration" to room.lobbyLifecycleGeneration(),
            "resetInProgress" to room.isGameOverResetInProgress(),
            "returnInProgress" to room.isLobbyPlayerSyncBlocked(),
            "fullSnapshotReady" to ((module.gameLinkFunction as? FullGameSyncLink)?.canSyncFullGameState() == true),
            "manualStartCountdownSeconds" to Data.configServer.manualStartCountdownSeconds,
            "startCountdownRemainingSeconds" to StartModeAutoStartSupport.countdownRemainingSeconds(),
            "clientWorldConsistency" to "unverified")
    }
}
