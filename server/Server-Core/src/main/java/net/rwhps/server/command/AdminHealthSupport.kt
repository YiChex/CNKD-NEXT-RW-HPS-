/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.command

import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.global.Data
import net.rwhps.server.data.global.NetStaticData
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.util.file.FileUtils
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.max

/**
 * Operator-facing health report for pure Start deployments.
 *
 * The report is intentionally conservative: it does not mutate runtime state,
 * so admins can run it during busy lobbies without adding extra player lag.
 */
object AdminHealthSupport {
    private const val LOW_HEAP_FOR_PUBLIC_SERVER_BYTES = 768L * 1024L * 1024L
    private const val LARGE_REPLAY_FOLDER_BYTES = 2L * 1024L * 1024L * 1024L
    private const val LARGE_CACHE_FOLDER_BYTES = 1L * 1024L * 1024L * 1024L
    private const val LARGE_MOD_FOLDER_BYTES = 2L * 1024L * 1024L * 1024L
    private const val FOLDER_SCAN_LIMIT = 20_000

    fun buildReport(includeStorageDetails: Boolean = false): List<String> {
        val lines = mutableListOf<String>()
        val advice = mutableListOf<String>()
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val usedPercent = percent(usedMemory, maxMemory)
        val memoryState = when {
            usedPercent >= 92.0 -> "CRITICAL"
            usedPercent >= 82.0 -> "WARN"
            else -> "OK"
        }

        lines += "========== CNKD HEALTH =========="
        lines += "overall=$memoryState"
        lines += "heap.used=${formatBytes(usedMemory)} / ${formatBytes(maxMemory)} (${formatPercent(usedPercent)})"
        lines += "heap.committed=${formatBytes(totalMemory)}, heap.freeCommitted=${formatBytes(freeMemory)}"
        lines += "cpu.availableProcessors=${runtime.availableProcessors()}"
        lines += buildRuntimeModeLine()
        lines += buildRoomLine()

        if (usedPercent >= 92.0) {
            advice += "内存已经接近溢出风险：建议先停止开新局，等待当前玩家结束后重启实例，并检查模组/回放/缓存占用。"
        } else if (usedPercent >= 82.0) {
            advice += "内存压力偏高：建议减少同时在线人数、关闭不必要模组，或为 Java 增加 -Xmx。"
        }

        val maxPlayer = runCatching { Data.configServer.maxPlayer }.getOrDefault(0)
        if (maxPlayer >= 60 && maxMemory in 1 until LOW_HEAP_FOR_PUBLIC_SERVER_BYTES) {
            advice += "当前 maxPlayer=$maxPlayer 但 JVM 最大堆低于 ${formatBytes(LOW_HEAP_FOR_PUBLIC_SERVER_BYTES)}，公开服高峰期更容易卡顿或 OOM。"
        }

        lines += "net.services=${NetStaticData.netService.size}"
        Threads.buildDiagnostics().forEach { lines += it }

        if (includeStorageDetails) {
            appendStorageReport(lines, advice)
        } else {
            lines += "storage=skipped(use `health detail` to scan data folders)"
        }

        appendConfigAdvice(advice)
        lines += "---------- advice ----------"
        if (advice.isEmpty()) {
            lines += "当前未发现明显高风险项。建议高峰期仍定期执行 `health detail` 和 `uplist status`。"
        } else {
            advice.distinct().forEachIndexed { index, item -> lines += "${index + 1}. $item" }
        }
        lines += "commands.next=health detail | config check | api status | startmode diagnose | uplist status"
        return lines
    }

    private fun buildRuntimeModeLine(): String {
        val pureStartMode = runCatching { Data.pureStartMode }.getOrDefault(false)
        val port = runCatching { Data.config.port }.getOrDefault(0)
        val autoUpList = runCatching { Data.config.autoUpList }.getOrDefault(false)
        val promotion = runCatching { Data.config.listPromotionModeEnabled }.getOrDefault(false)
        val experiment = runCatching { Data.config.listExperimentEnabled }.getOrDefault(false)
        return "mode.pureStart=$pureStartMode, port=$port, autoUpList=$autoUpList, promotion=$promotion, experiment=$experiment"
    }

    private fun buildRoomLine(): String {
        if (!HeadlessModuleManage.initHPS()) {
            return "room=headless_not_ready"
        }

        val room = HeadlessModuleManage.hps.room
        val presence = room.playerManage.buildPlayerPresenceSnapshot()
        val maxPlayer = runCatching { Data.configServer.maxPlayer }.getOrDefault(0)
        return "room.started=${room.isStartGame}, players=${presence.connectedOccupants}/$maxPlayer, reserved=${presence.reservedOccupants}, graceHumans=${presence.graceProtectedHumanCount}, reconnectGrace=${presence.reconnectGraceHumanCount}, map=${room.maps.mapName}"
    }

    private fun appendStorageReport(lines: MutableList<String>, advice: MutableList<String>) {
        val replayStat = folderStat(Data.Plugin_RePlays_Path)
        val cacheStat = folderStat(Data.ServerCachePath)
        val modStat = folderStat(Data.Plugin_Mods_Path)
        val mapStat = folderStat(Data.ServerMapsPath)
        val logStat = folderStat(Data.ServerLogPath)

        lines += replayStat.toLine("storage.replays")
        lines += cacheStat.toLine("storage.cache")
        lines += modStat.toLine("storage.mods")
        lines += mapStat.toLine("storage.maps")
        lines += logStat.toLine("storage.logs")

        val saveReplay = runCatching { Data.configServer.saveRePlayFile }.getOrDefault(true)
        if (saveReplay && replayStat.bytes >= LARGE_REPLAY_FOLDER_BYTES) {
            advice += "回放目录已超过 ${formatBytes(LARGE_REPLAY_FOLDER_BYTES)}：建议建立定期归档/清理策略，降低磁盘与备份成本。"
        }
        if (cacheStat.bytes >= LARGE_CACHE_FOLDER_BYTES) {
            advice += "缓存目录已超过 ${formatBytes(LARGE_CACHE_FOLDER_BYTES)}：建议在无人时停服清理 data/cache，减少启动扫描和磁盘成本。"
        }
        if (modStat.bytes >= LARGE_MOD_FOLDER_BYTES) {
            advice += "模组目录较大：建议只保留当前实例需要的模组，大型模组服可拆分实例，减少内存峰值和同步压力。"
        }
        if (replayStat.truncated || cacheStat.truncated || modStat.truncated || mapStat.truncated || logStat.truncated) {
            advice += "部分目录文件数量过多，health 已停止深扫以保护在线玩家；建议离峰期做离线清理和归档。"
        }
    }

    private fun appendConfigAdvice(advice: MutableList<String>) {
        val autoUpList = runCatching { Data.config.autoUpList }.getOrDefault(false)
        val listExperiment = runCatching { Data.config.listExperimentEnabled }.getOrDefault(false)
        val promotion = runCatching { Data.config.listPromotionModeEnabled }.getOrDefault(false)
        val saveReplay = runCatching { Data.configServer.saveRePlayFile }.getOrDefault(true)

        if (!autoUpList) {
            advice += "autoUpList=false：如果这是公开服，管理员需要手动 `uplist add`，否则玩家可能在列表上看不到服务器。"
        }
        if (listExperiment && !promotion) {
            advice += "实验字段已启用但推广模式未启用：建议优先使用推广模式，减少列表字段组合导致的进入体验不确定性。"
        }
        if (promotion) {
            advice += "推广模式已启用：请定期用 `uplist status` 确认 gs1/gs4 可见性，而不是只看上传成功日志。"
        }
        if (saveReplay) {
            advice += "当前启用回放保存：公开服长期运行建议配套自动清理，否则磁盘与备份成本会持续上涨。"
        }
    }

    private fun folderStat(path: String): FolderStat {
        val root = runCatching { FileUtils.getFolder(path, true).file }.getOrNull()
            ?: return FolderStat(0L, 0, 0, truncated = false, exists = false)
        if (!root.exists()) {
            return FolderStat(0L, 0, 0, truncated = false, exists = false)
        }

        var bytes = 0L
        var files = 0
        var dirs = 0
        var visited = 0
        val stack = ArrayDeque<File>()
        stack.add(root)

        while (!stack.isEmpty()) {
            val file = stack.removeLast()
            if (++visited > FOLDER_SCAN_LIMIT) {
                return FolderStat(bytes, files, dirs, truncated = true, exists = true)
            }
            if (file.isFile) {
                files++
                bytes += max(file.length(), 0L)
            } else if (file.isDirectory) {
                dirs++
                file.listFiles()?.forEach { child -> stack.add(child) }
            }
        }

        return FolderStat(bytes, files, dirs, truncated = false, exists = true)
    }

    private fun percent(value: Long, total: Long): Double {
        if (total <= 0L) {
            return 0.0
        }
        return value.toDouble() * 100.0 / total.toDouble()
    }

    private fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value)

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${bytes}B"
        } else {
            "${String.format(Locale.US, "%.1f", value)}${units[unitIndex]}"
        }
    }

    private data class FolderStat(
        val bytes: Long,
        val files: Int,
        val dirs: Int,
        val truncated: Boolean,
        val exists: Boolean
    ) {
        fun toLine(label: String): String {
            val state = if (exists) "exists" else "missing"
            val truncatedText = if (truncated) ", truncated=true" else ""
            return "$label=$state, size=${formatBytes(bytes)}, files=$files, dirs=$dirs$truncatedText"
        }
    }
}
