/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.api

import net.rwhps.server.data.bean.BeanRelayNodeConfig
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.room.RelayRoom
import java.util.Locale

internal object RelayNodeRoutingSupport {
    fun configuredNodes(): List<BeanRelayNodeConfig> {
        val configNodes = Data.configRelay.nodes
            .mapNotNull { normalizeNode(it) }
            .distinctBy { it.code.uppercase(Locale.ROOT) }

        if (configNodes.isNotEmpty()) {
            return configNodes
        }

        return Data.configRelay.relayRedirectRules
            .split(';')
            .mapNotNull { rule ->
                val item = rule.trim()
                if (item.isEmpty()) {
                    return@mapNotNull null
                }

                val pair = item.split('=', limit = 2)
                if (pair.size != 2) {
                    return@mapNotNull null
                }

                val code = pair[0].trim().uppercase(Locale.ROOT)
                val endpoint = pair[1].trim().trimEnd('/')
                if (code.length != 1 || endpoint.isEmpty()) {
                    return@mapNotNull null
                }

                val host = endpoint.substringBefore(':').trim()
                val port = endpoint.substringAfter(':', "5123").toIntOrNull() ?: 5123
                BeanRelayNodeConfig(
                    code = code,
                    name = code,
                    domain = host,
                    ip = host,
                    port = port
                )
            }
    }

    fun requestedNodeCode(id: String): String? {
        RelayDeploymentSupport.parseShareRoomNodeCode(id)?.let { return it }
        RelayDeploymentSupport.parseClusterRoomNodeCode(id)?.let { return it }
        RelayDeploymentSupport.parseBareForwardedNodeCode(id)?.let { return it }
        if (id.length < 2 || !id.startsWith("R", ignoreCase = true)) {
            return null
        }
        return id.substring(1, 2).uppercase(Locale.ROOT)
    }

    fun shouldHandleOnLocalNode(nodeCode: String): Boolean {
        val localCode = Data.configRelay.localNodeCode.trim().uppercase(Locale.ROOT)
        return localCode.isNotEmpty() && localCode == nodeCode.uppercase(Locale.ROOT)
    }

    fun findNode(code: String): BeanRelayNodeConfig? {
        if (!RelayLicenseSupport.allowCoreRouting()) {
            return null
        }
        val normalizedCode = code.trim().uppercase(Locale.ROOT)
        return configuredNodes().firstOrNull { it.code.trim().uppercase(Locale.ROOT) == normalizedCode }
    }

    fun redirectEndpoint(code: String): String? {
        if (!RelayLicenseSupport.allowCoreRouting()) {
            return null
        }
        val node = findNode(code) ?: return null
        val host = when {
            node.domain.isNotBlank() -> node.domain.trim()
            node.ip.isNotBlank() -> node.ip.trim()
            else -> return null
        }
        return "$host:${node.port}"
    }

    fun coreEndpoint(): String? {
        if (!RelayLicenseSupport.allowCoreRouting()) {
            return null
        }
        val host = Data.configRelay.mainServerIP.trim()
        val port = Data.configRelay.mainServerPort
        if (host.isBlank() || port <= 0) {
            return null
        }
        return "$host:$port"
    }

    fun selectNodeForPublicRoom(excludeCode: String = ""): BeanRelayNodeConfig? {
        if (!RelayLicenseSupport.allowCoreRouting()) {
            return null
        }
        RelayNodeRuntimeSupport.refreshIfNeeded()
        val excluded = excludeCode.trim().uppercase(Locale.ROOT)
        return configuredNodes()
            .filter {
                it.enabled &&
                    it.allowCreate &&
                    it.allowPublicRoom &&
                    effectiveUserPublicRoomRemaining(it) > 0 &&
                    nodeAllowsPublicRoom(it) &&
                    (excluded.isBlank() || it.code.uppercase(Locale.ROOT) != excluded)
            }
            .sortedWith(
                compareByDescending<BeanRelayNodeConfig> { effectiveUserPublicRoomRemaining(it) }
                    .thenByDescending { it.publicRoomBias }
                    .thenBy { observedRelayPlayerConnectionSize(it) }
                    .thenBy { observedTcpConnectionSize(it) }
                    .thenBy { observedRoomAllSize(it) }
                    .thenBy { it.code.uppercase(Locale.ROOT) }
            )
            .firstOrNull()
    }

    fun findNodeCodeWithObservedPublicRoomByHostIp(ip: String, excludeCode: String = ""): String? {
        if (!RelayLicenseSupport.allowCoreRouting()) {
            return null
        }
        RelayNodeRuntimeSupport.refreshIfNeeded()
        val normalizedIp = ip.trim()
        if (normalizedIp.isEmpty()) {
            return null
        }

        val excluded = excludeCode.trim().uppercase(Locale.ROOT)
        return configuredNodes()
            .mapNotNull { node ->
                val code = node.code.uppercase(Locale.ROOT)
                if (excluded.isNotEmpty() && code == excluded) {
                    return@mapNotNull null
                }
                val state = RelayNodeRuntimeSupport.freshState(code) ?: return@mapNotNull null
                if (state.publicRoomHostIps.any { it.equals(normalizedIp, ignoreCase = true) }) {
                    code
                } else {
                    null
                }
            }
            .firstOrNull()
    }

    fun uplistRemaining(node: BeanRelayNodeConfig): Int = effectiveUserPublicRoomRemaining(node)

    fun userPublicRoomLimit(node: BeanRelayNodeConfig): Int {
        val rawLimit = node.uplistLimit.coerceAtLeast(0)
        val reservedLimit = if (Data.configRelay.decision.reserveServerListSlot) {
            (rawLimit - 1).coerceAtLeast(0)
        } else {
            rawLimit
        }
        val configuredLimit = Data.configRelay.decision.userPublicRoomLimitPerNode.takeIf { it > 0 }
        return configuredLimit?.let { minOf(reservedLimit, it) } ?: reservedLimit
    }

    fun observedPublicRoomUsed(node: BeanRelayNodeConfig): Int {
        return RelayNodeRuntimeSupport.freshState(node.code)?.roomPublicSize ?: node.uplistUsed.coerceAtLeast(0)
    }

    fun effectiveUserPublicRoomRemaining(node: BeanRelayNodeConfig): Int {
        return (userPublicRoomLimit(node) - observedPublicRoomUsed(node)).coerceAtLeast(0)
    }

    fun localUserPublicRoomLimit(): Int {
        val localNode = Data.configRelay.localNodeCode.trim().takeIf { it.isNotBlank() }?.let(::findNode)
        return if (localNode != null) {
            userPublicRoomLimit(localNode)
        } else {
            Data.configRelay.decision.userPublicRoomLimitPerNode.coerceAtLeast(0)
        }
    }

    fun localPublicRoomRemaining(): Int {
        return (localUserPublicRoomLimit() - RelayRoom.roomPublicSize).coerceAtLeast(0)
    }

    fun canOpenPublicRoomLocally(existingPublicRoom: Boolean = false, ignoreLimit: Boolean = false): Boolean {
        if (ignoreLimit || existingPublicRoom) {
            return true
        }
        return localPublicRoomRemaining() > 0
    }

    fun localPublicRoomLimitMessage(): String {
        val coreEndpoint = coreEndpoint()
        val prefix = RelayDeploymentSupport.shareRoomPrefix().ifBlank { "RK" }
        return if (!Data.configRelay.mainServer && coreEndpoint != null) {
            "当前节点公开房名额已满，可直接使用列表中的 ${prefix} 系列服务器房号加入，或从核心入口重新创建公开房"
        } else {
            "当前节点公开房名额已满，可直接使用列表中的 ${prefix} 系列服务器房号加入，或稍后再试"
        }
    }

    private fun normalizeNode(node: BeanRelayNodeConfig): BeanRelayNodeConfig? {
        val code = node.code.trim().uppercase(Locale.ROOT)
        if (code.length != 1) {
            return null
        }
        return node.copy(code = code)
    }

    private fun nodeAllowsPublicRoom(node: BeanRelayNodeConfig): Boolean {
        val hasStatusUrl = RelayNodeRuntimeSupport.buildStatusUrl(node) != null
        if (Data.configRelay.mainServer && Data.configRelay.decision.enableNodeStatusAutoRefresh && hasStatusUrl) {
            return RelayNodeRuntimeSupport.freshState(node.code)?.upListEnabled == true
        }
        return RelayNodeRuntimeSupport.freshState(node.code)?.upListEnabled ?: true
    }

    private fun observedRelayPlayerConnectionSize(node: BeanRelayNodeConfig): Int {
        return RelayNodeRuntimeSupport.freshState(node.code)?.relayPlayerConnectionSize ?: Int.MAX_VALUE
    }

    private fun observedTcpConnectionSize(node: BeanRelayNodeConfig): Int {
        return RelayNodeRuntimeSupport.freshState(node.code)?.tcpConnectionSize ?: Int.MAX_VALUE
    }

    private fun observedRoomAllSize(node: BeanRelayNodeConfig): Int {
        return RelayNodeRuntimeSupport.freshState(node.code)?.roomAllSize ?: Int.MAX_VALUE
    }
}
