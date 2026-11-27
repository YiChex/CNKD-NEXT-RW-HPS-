/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.command

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.file.FileUtils
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.max

/**
 * Safe maintenance helpers for public-server operators.
 *
 * All destructive operations require an explicit `confirm` argument at command
 * level. The helper also constrains cleanup to known data subdirectories.
 */
object AdminMaintenanceSupport {
    private const val DEFAULT_KEEP_DAYS = 14
    private const val MAX_SCAN_ITEMS = 50_000
    private val managedCleanupFolders = setOf(
        Data.ServerCachePath,
        Data.Plugin_RePlays_Path,
        Data.ServerLogPath
    )

    fun buildPlan(keepDays: Int = DEFAULT_KEEP_DAYS): List<String> {
        val normalizedDays = normalizeKeepDays(keepDays)
        val cache = summarizeAllChildren(Data.ServerCachePath)
        val replays = summarizeOldFiles(Data.Plugin_RePlays_Path, normalizedDays)
        val logs = summarizeOldFiles(Data.ServerLogPath, normalizedDays)

        return listOf(
            "========== MAINTENANCE PLAN ==========",
            "policy.defaultKeepDays=$normalizedDays",
            "cache.cleanable=${cache.toDisplay()}",
            "replays.olderThan${normalizedDays}d=${replays.toDisplay()}",
            "logs.olderThan${normalizedDays}d=${logs.toDisplay()}",
            "safeGuard=默认只预览；必须输入 confirm 才会删除文件。",
            "commands.cleanCache=maintenance clean-cache confirm",
            "commands.cleanReplays=maintenance clean-replays confirm $normalizedDays",
            "commands.cleanLogs=maintenance clean-logs confirm $normalizedDays",
            "notice=不会清理 data/maps、data/mods、data/player、data/saves，避免影响玩家进入与存档。"
        )
    }

    fun cleanCache(confirm: Boolean): List<String> {
        if (!confirm) {
            return confirmationRequired("maintenance clean-cache confirm")
        }

        val folder = resolveManagedFolder(Data.ServerCachePath)
            ?: return listOf("maintenance.cleanCache=failed, reason=cache folder is outside managed data path")
        val before = summarizeAllChildren(Data.ServerCachePath)
        val deleted = deleteChildren(folder)
        val after = summarizeAllChildren(Data.ServerCachePath)

        return listOf(
            "maintenance.cleanCache=done",
            "before=${before.toDisplay()}",
            "deleted.files=${deleted.files}, deleted.dirs=${deleted.dirs}, failed=${deleted.failed}",
            "after=${after.toDisplay()}",
            "notice=如果玩家仍在大厅卡顿，请继续执行 health 查看线程队列和内存压力。"
        )
    }

    fun cleanReplays(confirm: Boolean, keepDays: Int = DEFAULT_KEEP_DAYS): List<String> {
        return cleanOldFiles(
            confirm = confirm,
            path = Data.Plugin_RePlays_Path,
            label = "cleanReplays",
            keepDays = keepDays,
            usage = "maintenance clean-replays confirm ${normalizeKeepDays(keepDays)}"
        )
    }

    fun cleanLogs(confirm: Boolean, keepDays: Int = DEFAULT_KEEP_DAYS): List<String> {
        return cleanOldFiles(
            confirm = confirm,
            path = Data.ServerLogPath,
            label = "cleanLogs",
            keepDays = keepDays,
            usage = "maintenance clean-logs confirm ${normalizeKeepDays(keepDays)}"
        )
    }

    private fun cleanOldFiles(confirm: Boolean, path: String, label: String, keepDays: Int, usage: String): List<String> {
        val normalizedDays = normalizeKeepDays(keepDays)
        if (!confirm) {
            return confirmationRequired(usage)
        }

        val folder = resolveManagedFolder(path)
            ?: return listOf("maintenance.$label=failed, reason=target folder is outside managed data path")
        val before = summarizeOldFiles(path, normalizedDays)
        val cutoffMillis = System.currentTimeMillis() - normalizedDays * 24L * 60L * 60L * 1000L
        val deleted = deleteMatchingFiles(folder) { file ->
            file.isFile && file.lastModified() in 1 until cutoffMillis
        }
        pruneEmptyDirectories(folder)
        val after = summarizeOldFiles(path, normalizedDays)

        return listOf(
            "maintenance.$label=done",
            "keepDays=$normalizedDays",
            "before=${before.toDisplay()}",
            "deleted.files=${deleted.files}, deleted.dirs=${deleted.dirs}, failed=${deleted.failed}",
            "after=${after.toDisplay()}",
            "notice=如果需要更激进清理，请先备份目录，再使用更小的 keepDays。"
        )
    }

    private fun confirmationRequired(usage: String): List<String> {
        return listOf(
            "maintenance=preview_only",
            "reason=为了保护玩家数据，清理操作需要显式 confirm。",
            "usage=$usage"
        )
    }

    private fun summarizeAllChildren(path: String): MaintenanceStat {
        val folder = resolveManagedFolder(path) ?: return MaintenanceStat(missing = true)
        if (!folder.exists()) {
            return MaintenanceStat(missing = true)
        }

        var files = 0
        var dirs = 0
        var bytes = 0L
        var visited = 0
        val stack = ArrayDeque<File>()
        folder.listFiles()?.forEach { stack.add(it) }

        while (!stack.isEmpty()) {
            val file = stack.removeLast()
            if (++visited > MAX_SCAN_ITEMS) {
                return MaintenanceStat(bytes, files, dirs, truncated = true)
            }
            if (Files.isSymbolicLink(file.toPath())) {
                files++
                bytes += max(file.length(), 0L)
            } else if (file.isFile) {
                files++
                bytes += max(file.length(), 0L)
            } else if (file.isDirectory) {
                dirs++
                file.listFiles()?.forEach { stack.add(it) }
            }
        }

        return MaintenanceStat(bytes, files, dirs)
    }

    private fun summarizeOldFiles(path: String, keepDays: Int): MaintenanceStat {
        val folder = resolveManagedFolder(path) ?: return MaintenanceStat(missing = true)
        if (!folder.exists()) {
            return MaintenanceStat(missing = true)
        }

        val cutoffMillis = System.currentTimeMillis() - normalizeKeepDays(keepDays) * 24L * 60L * 60L * 1000L
        var files = 0
        var bytes = 0L
        var visited = 0
        val stack = ArrayDeque<File>()
        stack.add(folder)

        while (!stack.isEmpty()) {
            val file = stack.removeLast()
            if (++visited > MAX_SCAN_ITEMS) {
                return MaintenanceStat(bytes, files, 0, truncated = true)
            }
            if (Files.isSymbolicLink(file.toPath())) {
                continue
            }
            if (file.isFile && file.lastModified() in 1 until cutoffMillis) {
                files++
                bytes += max(file.length(), 0L)
            } else if (file.isDirectory) {
                file.listFiles()?.forEach { stack.add(it) }
            }
        }

        return MaintenanceStat(bytes, files, 0)
    }

    private fun deleteChildren(folder: File): DeleteStat {
        val safeFolder = folder.canonicalFile
        var deletedFiles = 0
        var deletedDirs = 0
        var failed = 0

        safeFolder.listFiles()?.forEach { child ->
            val stat = deleteRecursivelyInside(safeFolder, child)
            deletedFiles += stat.files
            deletedDirs += stat.dirs
            failed += stat.failed
        }

        return DeleteStat(deletedFiles, deletedDirs, failed)
    }

    private fun deleteMatchingFiles(folder: File, predicate: (File) -> Boolean): DeleteStat {
        val safeFolder = folder.canonicalFile
        var deletedFiles = 0
        var failed = 0
        var visited = 0
        val stack = ArrayDeque<File>()
        stack.add(safeFolder)

        while (!stack.isEmpty()) {
            val file = stack.removeLast()
            if (++visited > MAX_SCAN_ITEMS) {
                failed++
                break
            }
            if (Files.isSymbolicLink(file.toPath())) {
                continue
            }
            if (file.isFile && predicate(file)) {
                if (isInside(safeFolder, file) && file.delete()) {
                    deletedFiles++
                } else {
                    failed++
                }
            } else if (file.isDirectory) {
                file.listFiles()?.forEach { stack.add(it) }
            }
        }

        return DeleteStat(files = deletedFiles, dirs = 0, failed = failed)
    }

    private fun deleteRecursivelyInside(root: File, target: File): DeleteStat {
        if (!isInside(root, target)) {
            return DeleteStat(failed = 1)
        }
        if (Files.isSymbolicLink(target.toPath()) || target.isFile) {
            return if (target.delete()) DeleteStat(files = 1) else DeleteStat(failed = 1)
        }
        if (!target.isDirectory) {
            return if (target.delete()) DeleteStat(files = 1) else DeleteStat(failed = 1)
        }

        var files = 0
        var dirs = 0
        var failed = 0
        target.listFiles()?.forEach { child ->
            val childStat = deleteRecursivelyInside(root, child)
            files += childStat.files
            dirs += childStat.dirs
            failed += childStat.failed
        }

        if (target.delete()) {
            dirs++
        } else {
            failed++
        }
        return DeleteStat(files, dirs, failed)
    }

    private fun pruneEmptyDirectories(folder: File): Int {
        if (!folder.exists() || !folder.isDirectory) {
            return 0
        }
        var deleted = 0
        folder.listFiles()?.forEach { child ->
            if (child.isDirectory && !Files.isSymbolicLink(child.toPath())) {
                deleted += pruneEmptyDirectories(child)
                if (child.listFiles()?.isEmpty() != false && isInside(folder, child) && child.delete()) {
                    deleted++
                }
            }
        }
        return deleted
    }

    private fun resolveManagedFolder(path: String): File? {
        if (path !in managedCleanupFolders) {
            return null
        }
        val dataRoot = FileUtils.getFolder(Data.ServerDataPath, true).file.canonicalFile
        val folder = FileUtils.getFolder(path, true).file.canonicalFile
        if (!isInside(dataRoot, folder)) {
            return null
        }
        return folder
    }

    private fun isInside(root: File, child: File): Boolean {
        val rootFile = root.canonicalFile
        val childFile = child.canonicalFile
        return childFile.path == rootFile.path || childFile.path.startsWith(rootFile.path + File.separator)
    }

    private fun normalizeKeepDays(days: Int): Int = days.coerceIn(1, 3650)

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

    private data class MaintenanceStat(
        val bytes: Long = 0L,
        val files: Int = 0,
        val dirs: Int = 0,
        val truncated: Boolean = false,
        val missing: Boolean = false
    ) {
        fun toDisplay(): String {
            if (missing) {
                return "missing"
            }
            val truncatedText = if (truncated) ", truncated=true" else ""
            return "size=${formatBytes(bytes)}, files=$files, dirs=$dirs$truncatedText"
        }
    }

    private data class DeleteStat(
        val files: Int = 0,
        val dirs: Int = 0,
        val failed: Int = 0
    )
}
