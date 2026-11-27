/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

internal data class PluginDiagnosticRecord(
    val fileName: String,
    val phase: String,
    val reason: String,
    val suggestion: String,
    val atMillis: Long = System.currentTimeMillis()
)

/** Bounded restart-scoped diagnostics for archives rejected before runtime loading. */
internal object PluginDiagnostics {
    private const val MAX_RECORDS = 256
    private const val REPORT_FILE_NAME = "plugin-diagnostics.txt"
    private val records = CopyOnWriteArrayList<PluginDiagnosticRecord>()

    @Volatile
    private var directory: File? = null

    fun reset(pluginDirectory: File) {
        records.clear()
        directory = pluginDirectory
    }

    fun record(fileName: String, phase: String, reason: String, suggestion: String = suggestionFor(phase)) {
        while (records.size >= MAX_RECORDS) {
            records.removeAt(0)
        }
        records.add(
            PluginDiagnosticRecord(
                fileName = normalize(fileName, 160),
                phase = normalize(phase, 48),
                reason = normalize(reason, 360),
                suggestion = normalize(suggestion, 360)
            )
        )
    }

    fun buildReport(filter: String? = null): List<String> {
        val normalizedFilter = filter?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
        val selected = records.filter { record ->
            normalizedFilter == null || record.fileName.lowercase(Locale.ROOT).contains(normalizedFilter)
        }
        val lines = ArrayList<String>(12 + selected.size * 5)
        lines.addAll(PluginRuntimeSettings.summaryLines())
        lines.add("pluginDiagnostics.reportFile=${File(directory ?: PluginRuntimeSettings.pluginDirectory(), REPORT_FILE_NAME).path}")
        lines.add("pluginDiagnostics.records=${selected.size}")
        selected.forEachIndexed { index, record ->
            lines.add("pluginDiagnostics[$index].file=${record.fileName}")
            lines.add("pluginDiagnostics[$index].phase=${record.phase}")
            lines.add("pluginDiagnostics[$index].reason=${record.reason}")
            lines.add("pluginDiagnostics[$index].suggestion=${record.suggestion}")
            lines.add("pluginDiagnostics[$index].at=${record.atMillis}")
        }
        if (selected.isEmpty()) {
            lines.add("pluginDiagnostics.note=no rejected archive recorded in this process")
        }
        return lines
    }

    fun writeReport() {
        val targetDirectory = directory ?: return
        runCatching {
            targetDirectory.mkdirs()
            val target = File(targetDirectory, REPORT_FILE_NAME).toPath()
            val temporary = File(targetDirectory, ".$REPORT_FILE_NAME.${System.nanoTime()}.tmp").toPath()
            val bytes = (buildReport().joinToString(System.lineSeparator()) + System.lineSeparator())
                .toByteArray(StandardCharsets.UTF_8)
            Files.write(
                temporary,
                bytes,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun suggestionFor(phase: String): String {
        return when (phase) {
            "archive" -> "修复归档格式、路径、重复条目或大小限制后重新校验"
            "descriptor" -> "修正根目录 plugin.json 字段并重新运行 SDK 校验器"
            "compatibility" -> "将 supportedVersions 改为当前 API 范围并重新构建插件"
            "contents" -> "移除打包的服务端或 API 类并确认主类为 Java 11 字节码"
            "authorization" -> "开发测试使用显式 development 模式；生产环境重新生成完整 plugin-lock.json"
            "fingerprint" -> "停服核对插件归档来源并重新审批整个插件集合"
            "load" -> "检查公开无参构造器、依赖打包和主类实现后重新校验"
            "dependency" -> "检查 V1 import 名称和依赖顺序；V2 请 shade/relocate 私有依赖"
            else -> "查看 plugin-diagnostics.txt 和对应插件源码"
        }
    }

    private fun normalize(value: String, maxLength: Int): String {
        return value.replace(Regex("[\\r\\n]+"), " ").trim().take(maxLength).ifBlank { "unknown" }
    }
}
