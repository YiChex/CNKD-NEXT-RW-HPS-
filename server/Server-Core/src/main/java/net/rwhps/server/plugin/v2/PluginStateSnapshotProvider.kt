package net.rwhps.server.plugin.v2

import net.rwhps.server.data.global.Data
import net.rwhps.server.game.GameMaps
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.manage.ModManage
import net.rwhps.server.plugin.api.MapKind
import net.rwhps.server.plugin.api.MapSnapshot
import net.rwhps.server.plugin.api.ModSnapshot
import net.rwhps.server.plugin.api.RoomPhase
import net.rwhps.server.plugin.api.RoomSnapshot
import net.rwhps.server.plugin.api.ServerConfigSnapshot
import net.rwhps.server.plugin.internal.headless.support.SingleRoomLowLatencySupport
import net.rwhps.server.plugin.internal.headless.support.StartModeLobbySupport
import java.util.Collections
import java.util.Locale

internal interface PluginStateSnapshotProvider {
    fun roomSnapshot(): RoomSnapshot
    fun mapSnapshot(): MapSnapshot
    fun modSnapshots(): List<ModSnapshot>
    fun configSnapshot(): ServerConfigSnapshot
}

/** Builds lightweight immutable copies without pausing or mutating the game thread. */
internal object LivePluginStateSnapshotProvider: PluginStateSnapshotProvider {
    private const val CORE_MOD_NAME = "RW-HPS CoreUnits"

    override fun roomSnapshot(): RoomSnapshot {
        val module = runCatching { if (HeadlessModuleManage.initHPS()) HeadlessModuleManage.hps else null }.getOrNull()
        if (module == null) {
            return RoomSnapshot(
                false,
                RoomPhase.STARTING,
                false,
                false,
                false,
                0,
                0,
                0,
                0,
                runCatching { Data.configServer.maxPlayer }.getOrDefault(0).coerceAtLeast(0)
            )
        }

        val room = module.room
        val players = room.playerManage.playerGroup.toList()
        val resetInProgress = room.isGameOverResetInProgress()
        val returnInProgress = room.isLobbyPlayerSyncBlocked()
        return RoomSnapshot(
            true,
            roomPhase(room.isStartGame, returnInProgress, resetInProgress),
            room.isStartGame,
            resetInProgress,
            returnInProgress,
            players.size,
            players.count { room.playerManage.isConnectedHumanPlayer(it) },
            players.count { it.isAi },
            players.count { !it.isAi && StartModeLobbySupport.isSpectatorTeam(it.team) },
            Data.configServer.maxPlayer.coerceAtLeast(0)
        )
    }

    override fun mapSnapshot(): MapSnapshot {
        val module = runCatching { if (HeadlessModuleManage.initHPS()) HeadlessModuleManage.hps else null }.getOrNull()
            ?: return MapSnapshot(false, "", MapKind.UNKNOWN, false, false, "")
        val maps = module.room.maps
        val mapData = maps.mapData
        val runtimeName = mapData?.runtimeFileName.orEmpty()
        return MapSnapshot(
            true,
            maps.mapName,
            mapKind(maps.mapType),
            maps.mapType != GameMaps.MapType.DefaultMap,
            isModProvidedRuntimeName(runtimeName),
            runtimeName
        )
    }

    override fun modSnapshots(): List<ModSnapshot> {
        val snapshots = runCatching { ModManage.getModsList().toList() }
            .getOrDefault(emptyList())
            .asSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && !it.equals(CORE_MOD_NAME, ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedBy { it.lowercase(Locale.ROOT) }
            .map { ModSnapshot(it, true) }
            .toList()
        return Collections.unmodifiableList(snapshots)
    }

    override fun configSnapshot(): ServerConfigSnapshot {
        val server = Data.configServer
        val startMode = Data.configStartMode
        return ServerConfigSnapshot(
            Data.config.serverName,
            server.maxPlayer,
            server.maxUnit,
            server.maxMessageLen,
            server.enableAI,
            server.oneAdmin,
            server.autoStartMinPlayerSize,
            server.autoStartCountdownSeconds,
            startMode.disableTeamChange,
            startMode.disablePositionChange,
            startMode.enableMapLock,
            SingleRoomLowLatencySupport.enabled(),
            server.defaultStartingCreditsAmount,
            server.disableNukes,
            server.defaultFogMode
        )
    }

    internal fun roomPhase(started: Boolean, returning: Boolean, resetting: Boolean): RoomPhase {
        return when {
            resetting -> RoomPhase.RESETTING
            returning -> RoomPhase.RETURNING
            started -> RoomPhase.PLAYING
            else -> RoomPhase.LOBBY
        }
    }

    internal fun mapKind(type: GameMaps.MapType): MapKind {
        return when (type) {
            GameMaps.MapType.DefaultMap -> MapKind.BUILT_IN
            GameMaps.MapType.CustomMap -> MapKind.CUSTOM
            GameMaps.MapType.SavedGames -> MapKind.SAVED_GAME
        }
    }

    internal fun isModProvidedRuntimeName(runtimeName: String): Boolean {
        return runtimeName.replace('\\', '/').startsWith("cnkd_mod_maps", ignoreCase = true)
    }
}
