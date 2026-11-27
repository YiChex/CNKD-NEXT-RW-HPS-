/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.data.global.Data
import net.rwhps.server.game.GameMaps
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.manage.MapManage
import net.rwhps.server.game.manage.ModCompatibilitySupport
import net.rwhps.server.game.manage.ModManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.inline.findField
import net.rwhps.server.util.inline.findMethod
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal object StartModeReadinessSupport {
    private val joinTipSentCount = AtomicLong(0L)
    private val joinTipSuppressedCount = AtomicLong(0L)
    private const val LARGE_MOD_WARNING_BYTES = 256L * 1024L * 1024L
    private val USER_MOD_ARCHIVE_EXTENSIONS = setOf("rwmod", "zip")

    fun onPlayerJoin(player: PlayerHess, room: ServerRoom, reconnectRecovered: Boolean) {
        if (reconnectRecovered || !joinReadinessTipsEnabled()) {
            joinTipSuppressedCount.incrementAndGet()
            return
        }

        buildPlayerJoinTips(room).forEach { player.sendSystemMessage(it) }
        joinTipSentCount.incrementAndGet()
    }

    fun buildDiagnostics(): List<String> {
        val lines = mutableListOf<String>()
        lines += "========== READINESS =========="
        lines += "joinTips.enabled=${joinReadinessTipsEnabled()}"
        lines += "joinTips.modWarningThreshold=${modWarningThreshold()}"
        lines += "joinTips.sent=${joinTipSentCount.get()}"
        lines += "joinTips.suppressed=${joinTipSuppressedCount.get()}"
        val lowLatencyConfig = lowLatencyRuntimeConfig()
        val lowLatencyProfile = lowLatencyConfig?.let { SingleRoomLowLatencyProfiles.currentName(it) } ?: "unavailable"
        lines += "joinTips.lowLatencyHint=${joinReadinessTipsEnabled() && lowLatencyConfig?.enabled == true}"
        lines += "joinTips.lowLatencyProfile=$lowLatencyProfile"
        lines += "headlessReady=${HeadlessModuleManage.initHPS()}"
        if (!HeadlessModuleManage.initHPS()) {
            lines += "state=headless_not_ready"
            return lines
        }

        val room = HeadlessModuleManage.hps.room
        lines += buildReadinessSnapshot(room).toDiagnosticLines()
        lines += room.buildBattleRoomLobbyRecoveryDiagnostics()
        lines += ModCompatibilitySupport.buildDiagnostics()
        return lines
    }

    fun buildPlayerJoinTips(room: ServerRoom): List<String> {
        val snapshot = buildReadinessSnapshot(room)
        val tips = mutableListOf<String>()
        tips += "当前地图 ${snapshot.mapName}，人数 ${snapshot.playerCount}/${snapshot.maxPlayer}。"
        buildLowLatencyJoinTip()?.let { tips += it }

        if (snapshot.mapLockEnabled) {
            tips += if (snapshot.mapLockResolved) {
                "自动服务器 | 地图已锁定 会保持排位地图"
            } else {
                "本房启用了锁图，但锁定地图未解析成功，请管理员检查 lockMap。"
            }
        }

        if (snapshot.modCount >= snapshot.modWarningThreshold && snapshot.modWarningThreshold > 0) {
            val modSizeText = snapshot.modArchiveBytes.takeIf { it > 0 }?.let { "，模组包约 ${formatBytes(it)}" }.orEmpty()
            tips += "本房检测到 ${snapshot.modCount} 个模组$modSizeText，客户端模组不一致时可能同步较慢或无法正常游玩。"
        }

        if (snapshot.largeModDetected) {
            tips += "本房模组体积较大，首次直连可能需要更久完成校验；若长时间停在 Connecting，请确认客户端已安装同版本模组。"
        }

        if (snapshot.customMap && !snapshot.customMapPrepared) {
            tips += "当前自定义地图尚未准备完成，若无法开局请管理员执行 reloadmaps 或检查 maps/mods。"
        }

        if (snapshot.autoStartEnabled) {
            tips += "自动服务器 | 满${snapshot.autoStartMinPlayerSize}人自动开局，倒计时 ${snapshot.autoStartCountdownSeconds} 秒。"
        }

        return tips
    }

    fun buildReadinessSnapshot(room: ServerRoom): ReadinessSnapshot {
        val customMap = room.maps.mapType != GameMaps.MapType.DefaultMap
        val mapData = room.maps.mapData
        val mapEngineReady = mapEngineReadiness().ready
        val engineModNames = runCatching { ModManage.getModsList().toList() }.getOrDefault(emptyList())
        val userModEntries = topLevelUserModEntries()
        val modNames = userModEntries.map { modDisplayName(it) }
        val modArchiveBytes = topLevelUserModBytes(userModEntries)
        val customMapCount = MapManage.mapsData.values.count { it.mapType == GameMaps.MapType.CustomMap }
        val savedGameCount = MapManage.mapsData.values.count { it.mapType == GameMaps.MapType.SavedGames }
        val autoStartMin = Data.configServer.autoStartMinPlayerSize
        val lockedMapName = StartModeMapLockSupport.lockedMapName()
        val presence = room.playerManage.buildPlayerPresenceSnapshot()
        return ReadinessSnapshot(
            mapName = room.maps.mapName.ifBlank { "<blank>" },
            mapType = room.maps.mapType.name,
            customMap = customMap,
            customMapPrepared = !customMap || mapData != null,
            mapEngineReady = mapEngineReady,
            customMapRuntimeFile = mapData?.runtimeFileName ?: "",
            mapLockEnabled = StartModeMapLockSupport.isEnabled(),
            mapLockResolved = lockedMapName != null,
            lockedMapName = lockedMapName ?: "",
            availableCustomMaps = customMapCount,
            availableSavedGames = savedGameCount,
            modCount = modNames.size,
            modNamesPreview = modNames.take(8),
            modArchiveBytes = modArchiveBytes,
            largeModDetected = modArchiveBytes >= LARGE_MOD_WARNING_BYTES,
            modWarningThreshold = modWarningThreshold(),
            engineModCount = engineModNames.size,
            engineModNamesPreview = engineModNames.take(8),
            modPackageMapDiscoveryEnabled = ModCompatibilitySupport.modPackageMapDiscoveryEnabled(),
            playerCount = presence.connectedOccupants,
            reservedPlayerCount = presence.reservedOccupants,
            connectedHumanCount = presence.connectedHumanCount,
            graceProtectedHumanCount = presence.graceProtectedHumanCount,
            reconnectGraceHumanCount = presence.reconnectGraceHumanCount,
            maxPlayer = Data.configServer.maxPlayer,
            autoStartEnabled = autoStartMin != -1,
            autoStartMinPlayerSize = autoStartMin,
            autoStartCountdownSeconds = Data.configServer.autoStartCountdownSeconds.coerceAtLeast(0),
            lobbySyncOptimizationEnabled = StartModeLobbySupport.lobbySyncOptimizationEnabled(),
            reconnectGraceMillis = StartModePlayerExperienceSupport.reconnectGraceMillis()
        )
    }

    private fun joinReadinessTipsEnabled(): Boolean {
        return Data.configStartMode?.enableJoinReadinessTips ?: false
    }

    internal data class MapEngineReadiness(
        val available: Boolean,
        val ready: Boolean,
        val error: String?
    )

    /**
     * Probe the game engine through the Headless class loader. The server/plugin
     * loader does not necessarily contain optional libRocket classes such as
     * Root, so loading the plugin facade directly can poison GameEngine for the
     * rest of the process.
     */
    internal fun mapEngineReadiness(): MapEngineReadiness {
        val module = runCatching {
            if (!HeadlessModuleManage.initHPS()) null else HeadlessModuleManage.hps
        }.getOrNull() ?: return MapEngineReadiness(false, false, "headless_not_ready")

        return runCatching {
            val engineClass = Class.forName(
                "com.corrodinggames.rts.gameFramework.l",
                false,
                module.useClassLoader
            )
            val engine = engineClass.findMethod("B")?.invoke(null)
                ?: return@runCatching MapEngineReadiness(true, false, "engine_instance_unavailable")
            val mapEngine = engineClass.findField("bL")?.get(engine)
                ?: return@runCatching MapEngineReadiness(true, false, "map_engine_unavailable")
            val ready = mapEngine.javaClass.findField("W")?.get(mapEngine) as? Boolean ?: false
            MapEngineReadiness(true, ready, null)
        }.getOrElse { error ->
            MapEngineReadiness(
                available = false,
                ready = false,
                error = error.javaClass.simpleName.ifBlank { "map_engine_unavailable" }
            )
        }
    }

    internal fun mapEngineReadyOrFalse(probe: () -> Boolean): Boolean {
        return runCatching(probe).getOrDefault(false)
    }

    private fun modWarningThreshold(): Int {
        return (Data.configStartMode?.joinTipModWarningThreshold ?: 1).coerceIn(0, 1000)
    }

    private fun buildLowLatencyJoinTip(): String? {
        val config = lowLatencyRuntimeConfig() ?: return null
        if (!config.enabled) {
            return null
        }

        val profileText = when (SingleRoomLowLatencyProfiles.currentName(config)) {
            "A.observe" -> "观察档"
            "B.recommended" -> "推荐档"
            "C.aggressive" -> "激进实测档"
            "D.rollback" -> "稳健档"
            "custom" -> "自定义档"
            else -> "当前档"
        }
        return "本房已开启低延迟模式（$profileText）；若出现明显卡顿、回弹或不同步，请赛后反馈体感。"
    }

    private fun lowLatencyRuntimeConfig(): SingleRoomLowLatencySupport.RuntimeConfig? {
        return runCatching { SingleRoomLowLatencySupport.runtimeConfig() }.getOrNull()
    }

    data class ReadinessSnapshot(
        val mapName: String,
        val mapType: String,
        val customMap: Boolean,
        val customMapPrepared: Boolean,
        val mapEngineReady: Boolean,
        val customMapRuntimeFile: String,
        val mapLockEnabled: Boolean,
        val mapLockResolved: Boolean,
        val lockedMapName: String,
        val availableCustomMaps: Int,
        val availableSavedGames: Int,
        val modCount: Int,
        val modNamesPreview: List<String>,
        val modArchiveBytes: Long,
        val largeModDetected: Boolean,
        val modWarningThreshold: Int,
        val engineModCount: Int,
        val engineModNamesPreview: List<String>,
        val modPackageMapDiscoveryEnabled: Boolean,
        val playerCount: Int,
        val reservedPlayerCount: Int,
        val connectedHumanCount: Int,
        val graceProtectedHumanCount: Int,
        val reconnectGraceHumanCount: Int,
        val maxPlayer: Int,
        val autoStartEnabled: Boolean,
        val autoStartMinPlayerSize: Int,
        val autoStartCountdownSeconds: Int,
        val lobbySyncOptimizationEnabled: Boolean,
        val reconnectGraceMillis: Long
    ) {
        fun toDiagnosticLines(): List<String> {
            return listOf(
                "map.name=$mapName",
                "map.type=$mapType",
                "map.custom=$customMap",
                "map.customPrepared=$customMapPrepared",
                "map.engineReady=$mapEngineReady",
                "map.runtimeFile=${customMapRuntimeFile.ifBlank { "<blank>" }}",
                "map.lockEnabled=$mapLockEnabled",
                "map.lockResolved=$mapLockResolved",
                "map.lockedName=${lockedMapName.ifBlank { "<blank>" }}",
                "maps.customAvailable=$availableCustomMaps",
                "maps.savedGamesAvailable=$availableSavedGames",
                "mods.count=$modCount",
                "mods.preview=${modNamesPreview.joinToString(",").ifBlank { "<none>" }}",
                "mods.archiveBytes=$modArchiveBytes",
                "mods.largeDetected=$largeModDetected",
                "mods.engineCount=$engineModCount",
                "mods.enginePreview=${engineModNamesPreview.joinToString(",").ifBlank { "<none>" }}",
                "mods.packageMapDiscovery=$modPackageMapDiscoveryEnabled",
                "players=$playerCount/$maxPlayer",
                "players.reserved=$reservedPlayerCount/$maxPlayer",
                "players.connectedHumans=$connectedHumanCount",
                "players.graceProtectedHumans=$graceProtectedHumanCount",
                "players.reconnectGraceHumans=$reconnectGraceHumanCount",
                "autostart.enabled=$autoStartEnabled",
                "autostart.minPlayers=$autoStartMinPlayerSize",
                "autostart.countdownSeconds=$autoStartCountdownSeconds",
                "lobby.syncOptimization=$lobbySyncOptimizationEnabled",
                "reconnect.graceMillis=${String.format(Locale.US, "%d", reconnectGraceMillis)}"
            )
        }
    }

    internal fun topLevelUserModEntries(folder: File = FileUtils.getFolder(Data.Plugin_Mods_Path).file): List<File> {
        return runCatching {
            folder.listFiles()
                ?.asSequence()
                ?.filter { isUserModEntry(it) }
                ?.sortedBy { it.name.lowercase(Locale.US) }
                ?.toList()
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun isUserModEntry(file: File): Boolean {
        if (file.isFile) {
            return file.extension.lowercase(Locale.US) in USER_MOD_ARCHIVE_EXTENSIONS
        }

        return file.isDirectory && isUnpackedModFolder(file)
    }

    private fun isUnpackedModFolder(folder: File): Boolean {
        return File(folder, "mod-info.txt").isFile ||
                File(folder, "mod_info.txt").isFile ||
                File(folder, "units").isDirectory ||
                File(folder, "maps").isDirectory
    }

    private fun topLevelUserModBytes(entries: List<File>): Long {
        return entries.asSequence()
            .filter { it.isFile && it.extension.lowercase(Locale.US) in USER_MOD_ARCHIVE_EXTENSIONS }
            .sumOf { it.length() }
    }

    private fun modDisplayName(file: File): String {
        return if (file.isFile) file.nameWithoutExtension else file.name
    }

    private fun formatBytes(bytes: Long): String {
        val mib = bytes.toDouble() / 1024.0 / 1024.0
        return if (mib >= 1024.0) {
            String.format(Locale.US, "%.2fGB", mib / 1024.0)
        } else {
            String.format(Locale.US, "%.0fMB", mib)
        }
    }
}
