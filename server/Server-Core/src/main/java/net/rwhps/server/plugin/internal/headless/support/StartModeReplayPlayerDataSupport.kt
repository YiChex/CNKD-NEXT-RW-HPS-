/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.file.FileName.getFileNameNoSuffix
import net.rwhps.server.util.file.FileUtils

/**
 * 把"本局玩家数据"压缩包写到回放文件旁（同名 + `.zip`）。
 *
 * 这是**通用**能力，与排位无关：`Event.kt` 在每次对局结束、`configServer.saveRePlayFile` 为真时调用它，
 * 与是否启用排位平台没有任何关系。排位剥离时（用户决策 2）它被保留下来，只是从 `Ranked*` 命名中迁出。
 *
 * 原文件名为 `StartModeReplayPlayerDataSupport.kt`，其唯一与排位有关的痕迹是兜底文件名里的 `ranked-` 前缀，
 * 已改为 `replay-players-`。
 */
internal object StartModeReplayPlayerDataSupport {
    fun writePlayerDataZip(replayFileName: String, bytes: ByteArray): String {
        val fileName = playerDataZipName(replayFileName)
        val target = FileUtils.getFolder(Data.Plugin_RePlays_Path).toFile(fileName)
        target.writeFileByte(bytes)
        return target.path
    }

    internal fun playerDataZipName(replayFileName: String): String {
        val baseName = replayFileName
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()
            .replace(Regex("[\\r\\n\\t]"), "_")
            .ifBlank { "replay-players-${System.currentTimeMillis()}" }
        return "${getFileNameNoSuffix(baseName)}.zip"
    }
}