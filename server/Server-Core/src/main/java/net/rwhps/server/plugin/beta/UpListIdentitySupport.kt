/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AFFERO GENERAL PUBLIC LICENSE version 3.
 */

package net.rwhps.server.plugin.beta

import net.rwhps.server.util.algorithms.digest.DigestUtils
import java.io.File

internal object UpListIdentitySupport {
    fun buildServerId(
        serverConnectUuid: String,
        dataPath: String,
        roomId: String?,
        listenPort: Int,
        listEntryId: String? = null
    ): String {
        val scope = resolveScope(roomId, listEntryId)
        val deploymentPath = normalizePath(dataPath)
        return "u_" + DigestUtils.sha256Hex(
            "$serverConnectUuid|$deploymentPath|$scope|$listenPort"
        ).take(32).lowercase()
    }

    fun buildLegacyServerId(
        serverConnectUuid: String,
        roomId: String?,
        listenPort: Int,
        listEntryId: String? = null
    ): String {
        val scope = resolveScope(roomId, listEntryId)
        return "u_" + DigestUtils.sha256Hex(
            "$serverConnectUuid|$scope|$listenPort"
        ).take(32).lowercase()
    }

    private fun resolveScope(roomId: String?, listEntryId: String?): String {
        return roomId?.trim().takeUnless { it.isNullOrBlank() }
            ?: listEntryId?.trim()?.takeIf { it.isNotBlank() }?.let { "global:$it" }
            ?: "global"
    }

    private fun normalizePath(path: String): String {
        return runCatching { File(path).canonicalPath }
            .getOrElse { File(path).absolutePath }
            .replace('\\', '/')
            .trimEnd('/')
    }
}
