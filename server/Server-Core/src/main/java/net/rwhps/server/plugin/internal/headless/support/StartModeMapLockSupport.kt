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
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.ServerInfoPacket
import net.rwhps.server.util.inline.findField
import net.rwhps.server.util.inline.findMethod
import net.rwhps.server.util.inline.toClass
import net.rwhps.server.util.log.Log
import java.util.Locale

internal object StartModeMapLockSupport {
    private val applyLock = Any()

    private var lastWarnKey = ""

    fun isEnabled(): Boolean = Data.configStartMode.enableMapLock

    fun lockedMapName(): String? = resolveLockTarget()?.mapName

    fun applyLockedMapIfNeeded(): Boolean {
        val target = resolveLockTarget() ?: return false
        return synchronized(applyLock) {
            applyMapTarget(target)
        }
    }

    /*
     * Bootstrap runs before the original game loop has initialized its map
     * object. Preset network map fields here and let the engine load the map
     * when the room starts; do not queue a game-thread refresh at this stage.
     */
    fun applyLockedMapDuringBootstrap(): Boolean {
        val target = resolveLockTarget() ?: return false
        if (!HeadlessModuleManage.initHPS()) {
            return false
        }
        return synchronized(applyLock) {
            val prepared = runCatching {
                target.mapData.readMap()
                true
            }.getOrElse { error ->
                Log.warn("[StartModeMapLock] Locked map file is not ready during bootstrap: ${target.mapName}", error)
                false
            }
            if (!prepared) {
                return@synchronized false
            }

            val module = HeadlessModuleManage.hps
            if (!applyBootstrapRuntimeMapTarget(module, target)) {
                Log.warn("[StartModeMapLock] Failed to preset locked map during bootstrap: ${target.mapName}")
                return@synchronized false
            }

            val room = module.room
            room.maps.mapData = target.mapData
            room.maps.mapType = target.mapData.mapType
            room.maps.mapName = target.mapName
            room.maps.mapPlayer = ""
            true
        }
    }

    fun buildDiagnostics(): List<String> {
        val lines = mutableListOf<String>()
        lines += "========== MAPLOCK =========="
        lines += "enabled=${isEnabled()}"
        lines += "configuredLockMap=${Data.configStartMode.lockMap.ifBlank { "<blank>" }}"
        lines += "resolvedLockedMap=${lockedMapName() ?: "<unresolved>"}"
        lines += "headlessReady=${HeadlessModuleManage.initHPS()}"
        if (HeadlessModuleManage.initHPS()) {
            val room = HeadlessModuleManage.hps.room
            lines += "currentMapName=${room.maps.mapName}"
            lines += "currentMapType=${room.maps.mapType.name}"
            lines += "currentMapDataLoaded=${room.maps.mapData != null}"
            lines += "sameAsLocked=${lockedMapName()?.let { sameMap(room.maps.mapName, it) } ?: false}"
        }
        return lines
    }

    fun rewriteIncomingServerInfoPacket(packet: Packet, notify: ((String) -> Unit)? = null): Packet {
        if (!isEnabled()) {
            clearWarnings()
            return packet
        }

        val desiredMapName = lockedMapName() ?: currentMapName()
        val incomingMapName = runCatching { ServerInfoPacket(packet.bytes).mapName.trim() }.getOrDefault("")
        if (incomingMapName.isBlank() || sameMap(incomingMapName, desiredMapName)) {
            return packet
        }

        notify?.invoke(
            if (lockedMapName() != null) {
                "Map lock enabled. Keeping locked map: $desiredMapName"
            } else {
                "Map lock is enabled, but lockMap is empty or unavailable. Keeping current map: $desiredMapName"
            }
        )

        return Packet(packet.type, ServerInfoPacket.rewriteMapName(packet.bytes, desiredMapName))
    }

    private fun applyMapTarget(target: LockedMapTarget): Boolean {
        if (!HeadlessModuleManage.initHPS()) {
            return false
        }

        val module = HeadlessModuleManage.hps
        val prepared = runCatching {
            target.mapData.readMap()
            true
        }.getOrElse { error ->
            Log.warn("[StartModeMapLock] Locked map file is not ready: ${target.mapName}", error)
            false
        }
        if (!prepared) {
            return false
        }

        var changed = false
        var roomStartedBeforeDispatch = false
        var battleRoomRecoveryBeforeDispatch = false
        val operationSucceeded = module.gameFunction.suspendMainThreadOperations {
            val room = module.room
            if (room.isStartGame) {
                roomStartedBeforeDispatch = true
                return@suspendMainThreadOperations
            }
            if (room.isBattleRoomLobbyRecoveryInProgress()) {
                battleRoomRecoveryBeforeDispatch = true
                return@suspendMainThreadOperations
            }
            if (StartModeAutoStartSupport.isStartOperationInProgress()) {
                return@suspendMainThreadOperations
            }

            val currentData = room.maps.mapData
            val alreadyLocked =
                sameMap(room.maps.mapName, target.mapName) &&
                    room.maps.mapType == target.mapData.mapType &&
                    currentData?.runtimeFileName == target.mapData.runtimeFileName
            if (alreadyLocked) {
                return@suspendMainThreadOperations
            }

            check(applyRuntimeMapTarget(module, target)) { "locked_map_runtime_apply_failed" }
            room.maps.mapData = target.mapData
            room.maps.mapType = target.mapData.mapType
            room.maps.mapName = target.mapName
            room.maps.mapPlayer = ""
            changed = true
        }
        if (!operationSucceeded) {
            Log.warn("[StartModeMapLock] Failed to apply locked map on the game thread: ${target.mapName}")
        } else if (roomStartedBeforeDispatch) {
            Log.warn("[StartModeMapLock] Ignored locked map apply while a game is in progress: ${target.mapName}")
        }
        return operationSucceeded && !roomStartedBeforeDispatch && !battleRoomRecoveryBeforeDispatch && changed
    }

    private fun applyRuntimeMapTarget(
        module: net.rwhps.server.game.headless.core.AbstractGameModule,
        target: LockedMapTarget
    ): Boolean {
        return try {
            val loader = module.useClassLoader
            val engineClass = "com.corrodinggames.rts.gameFramework.l".toClass(loader) ?: return false
            val engine = engineClass.findMethod("B")?.invoke(null) ?: return false
            val netEngine = engineClass.findField("bX")?.get(engine) ?: return false
            val netEngineClass = netEngine.javaClass
            val mapSettings = netEngineClass.findField("ay")?.get(netEngine) ?: return false
            val mapSettingsClass = mapSettings.javaClass

            val mapFileName = target.mapData.runtimeFileName
            val mapRuntimePath = "/SD/rusted_warfare_maps/$mapFileName"

            netEngineClass.findField("az")?.set(netEngine, mapRuntimePath)

            val aiClass = "com.corrodinggames.rts.gameFramework.j.ai".toClass(loader) ?: return false
            val enumConstants = aiClass.enumConstants ?: return false
            val modeValue = enumConstants.getOrNull(target.mapData.mapType.ordinal) ?: return false
            mapSettingsClass.findField("a")?.set(mapSettings, modeValue)
            mapSettingsClass.findField("b")?.set(mapSettings, mapFileName)

            StartModeSurvivalMapSupport.recordSelectedMapName(mapRuntimePath)
            netEngineClass.findMethod("L")?.invoke(netEngine)
            true
        } catch (e: Exception) {
            Log.error("[StartModeMapLock] Failed to apply runtime map target", e)
            false
        }
    }

    private fun applyBootstrapRuntimeMapTarget(
        module: net.rwhps.server.game.headless.core.AbstractGameModule,
        target: LockedMapTarget
    ): Boolean {
        return try {
            val loader = module.useClassLoader
            val engineClass = "com.corrodinggames.rts.gameFramework.l".toClass(loader) ?: return false
            val engine = engineClass.findMethod("B")?.invoke(null) ?: return false
            val netEngine = engineClass.findField("bX")?.get(engine) ?: return false
            val netEngineClass = netEngine.javaClass
            val mapSettings = netEngineClass.findField("ay")?.get(netEngine) ?: return false
            val mapSettingsClass = mapSettings.javaClass
            val mapFileName = target.mapData.runtimeFileName
            val mapRuntimePath = "/SD/rusted_warfare_maps/$mapFileName"
            netEngineClass.findField("az")?.set(netEngine, mapRuntimePath)

            val aiClass = "com.corrodinggames.rts.gameFramework.j.ai".toClass(loader) ?: return false
            val modeValue = aiClass.enumConstants?.getOrNull(target.mapData.mapType.ordinal) ?: return false
            mapSettingsClass.findField("a")?.set(mapSettings, modeValue)
            mapSettingsClass.findField("b")?.set(mapSettings, mapFileName)
            StartModeSurvivalMapSupport.recordSelectedMapName(mapRuntimePath)
            true
        } catch (error: Exception) {
            Log.error("[StartModeMapLock] Failed to preset bootstrap map target", error)
            false
        }
    }

    private fun resolveLockTarget(): LockedMapTarget? {
        if (!isEnabled()) {
            clearWarnings()
            return null
        }

        val configured = Data.configStartMode.lockMap.trim()
        if (configured.isBlank()) {
            warnOnce(
                "blank",
                "已启用锁定地图，但 lockMap 为空。请在配置文件中填写 maps 文件夹内的地图名称。"
            )
            return null
        }

        val exactTarget = findCustomMap(configured)
        if (exactTarget != null) {
            clearWarnings()
            return exactTarget
        }

        warnOnce(
            "missing:$configured",
            "已启用锁定地图，但未找到指定地图：$configured。请确认 .tmx 已上传到 maps 文件夹，并执行 reloadmaps。"
        )
        return null
    }

    private fun findCustomMap(rawName: String): LockedMapTarget? {
        MapManage.resolveCustomMap(rawName)?.let { target ->
            return LockedMapTarget(target.mapName, target.mapData)
        }

        val exact = normalizeComparableName(rawName)
        val loose = normalizeLooseName(rawName)

        for (candidate in MapManage.mapsData.keys) {
            val mapKey = candidate ?: continue
            val mapData = MapManage.mapsData[mapKey] ?: continue
            if (mapData.mapType != GameMaps.MapType.CustomMap) {
                continue
            }
            val mapName = mapData.mapFileName

            if (normalizeComparableName(mapName).equals(exact, ignoreCase = true)) {
                return LockedMapTarget(mapName, mapData)
            }
        }

        for (candidate in MapManage.mapsData.keys) {
            val mapKey = candidate ?: continue
            val mapData = MapManage.mapsData[mapKey] ?: continue
            if (mapData.mapType != GameMaps.MapType.CustomMap) {
                continue
            }
            val mapName = mapData.mapFileName

            if (normalizeLooseName(mapName) == loose) {
                return LockedMapTarget(mapName, mapData)
            }
        }

        return null
    }

    private fun currentMapName(): String {
        return if (HeadlessModuleManage.initHPS()) {
            HeadlessModuleManage.hps.room.maps.mapName.trim().ifBlank { "Crossing Large (10p)" }
        } else {
            "Crossing Large (10p)"
        }
    }

    private fun sameMap(a: String, b: String): Boolean {
        return normalizeComparableName(a).equals(normalizeComparableName(b), ignoreCase = true)
    }

    private fun normalizeComparableName(rawName: String): String {
        var normalized = rawName
            .trim()
            .trim('"', '\'')
            .trimStart('\u0001')
            .substringAfterLast('\\')
            .substringAfterLast('/')
            .trim()

        normalized = normalized.removeSuffix(".tmx")
        normalized = normalized.removeSuffix(".TMX")
        normalized = normalized.removeSuffix(".rwsave")
        normalized = normalized.removeSuffix(".RWSAVE")

        return normalized
    }

    private fun normalizeLooseName(rawName: String): String {
        return normalizeComparableName(rawName)
            .replace(" ", "")
            .replace("_", "")
            .replace("-", "")
            .lowercase(Locale.ROOT)
    }

    private fun warnOnce(key: String, message: String) {
        if (lastWarnKey == key) {
            return
        }
        lastWarnKey = key
        Log.warn(message)
    }

    private fun clearWarnings() {
        lastWarnKey = ""
    }

    private data class LockedMapTarget(
        val mapName: String,
        val mapData: GameMaps.MapData
    )
}
