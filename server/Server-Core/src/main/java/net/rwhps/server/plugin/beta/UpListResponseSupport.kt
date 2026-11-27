/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.beta

internal object UpListResponseSupport {
    private val explicitFailureMarkers = listOf(
        "[FAILED]",
        "ERROR_",
        "DENIED",
        "REJECTED"
    )

    fun isUpdateAccepted(response: String): Boolean {
        if (response.isBlank()) {
            return false
        }
        if (hasExplicitFailureMarker(response)) {
            return false
        }
        return response.lineSequence()
            .map(String::trim)
            .any { line -> line.equals("GAME UPDATED", ignoreCase = true) }
    }

    /**
     * 检测 `action=self_info` 端口探测是否成功。
     *
     * 主服务器返回两行：
     * ```
     * CORRODINGGAMES[1.0]
     * 61.184.8.252,true
     * ```
     * **判定依据是 payload 行最后一个逗号分隔字段**，不是整行。
     *
     * 【真实 BUG，已线上复现】原实现要求某一整行 `equals("true")`，而实际响应是
     * `101.33.68.226,false` 这种形式 ⇒ 永远返回 false ⇒ `portOpen` **恒为 false**。
     * 后果（见 UpListMain 相关分支）：
     *   1. 每次都打印「[GLOBAL] 端口未开放」，即使端口正常监听（用户长期观察到的现象）；
     *   2. `gs1Uploaded/gs4Uploaded` 在 add 失败时无法靠 `previousUploaded && portOpen` 保持，
     *      导致上传状态被误判为丢失；
     *   3. 「端口可达但上传被拒」这条诊断分支永远走不到，排障信息缺失。
     *
     * 实测证据（2026-10-21，只读探测 gs1 `action=self_info`）：
     *   对 6 个**端口确实在监听**的官方 Auto Server 条目探测，
     *   响应均为 `CORRODINGGAMES[1.0]\n<ip>,false` ⇒ 原实现判定全部为 false。
     *
     * 兼容性：仍接受裸 `true` 行（旧/替代响应方与既有测试夹具）；
     * 不含逗号的行保持历史整行比较，因此 `error: true` 这类散文行仍会被拒绝。
     */
    fun isPortOpenResponse(response: String): Boolean {
        return response.lineSequence()
            .map(String::trim)
            .any { line -> isPortOpenLine(line) }
    }

    private fun isPortOpenLine(line: String): Boolean {
        if (line.isEmpty()) {
            return false
        }
        if (line.equals("true", ignoreCase = true)) {
            return true
        }
        val lastField = line.substringAfterLast(',', missingDelimiterValue = "")
        if (lastField.isEmpty()) {
            return false
        }
        return lastField.trim().equals("true", ignoreCase = true)
    }

    fun isAddAccepted(response: String, serverId: String): Boolean {
        if (serverId.isBlank() || response.isBlank() || hasExplicitFailureMarker(response)) {
            return false
        }
        return response.contains(serverId)
    }

    fun isExplicitNotFound(response: String): Boolean {
        return response.lineSequence()
            .map(String::trim)
            .map(String::uppercase)
            .any { line ->
                line == "NOT_FOUND" ||
                    line.startsWith("NOT_FOUND:") ||
                    line == "GAME NOT FOUND" ||
                    line.startsWith("GAME NOT FOUND:") ||
                    line == "ERROR_NOT_FOUND" ||
                    line.startsWith("ERROR_NOT_FOUND:") ||
                    line == "ERROR_GAME_NOT_FOUND" ||
                    line.startsWith("ERROR_GAME_NOT_FOUND:") ||
                    line.startsWith("HTTP_404") ||
                    line.startsWith("HTTP_410")
            }
    }

    fun hasExplicitFailureMarker(response: String): Boolean {
        return explicitFailureMarkers.any { marker -> response.contains(marker, ignoreCase = true) }
    }
}
