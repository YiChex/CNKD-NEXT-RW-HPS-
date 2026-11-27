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
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal data class RelayObservedNodeState(
    val code: String,
    val nodeName: String,
    val statusUrl: String,
    val reachable: Boolean,
    val lastAttemptTimestamp: Long,
    val lastSuccessTimestamp: Long,
    val remoteUpdateTimestamp: Long,
    val roomAllSize: Int,
    val roomPublicSize: Int,
    val roomPrivateSize: Int,
    val onlineSize: Int,
    val tcpConnectionSize: Int,
    val httpConnectionSize: Int,
    val relayPlayerConnectionSize: Int,
    val publicRoomHostIps: Set<String>,
    val upListEnabled: Boolean,
    val mainServer: Boolean,
    val localNodeCode: String,
    val message: String = ""
)

internal object RelayNodeRuntimeSupport {
    private val nodeStates = ConcurrentHashMap<String, RelayObservedNodeState>()

    @Volatile
    private var lastRefreshTimestamp = 0L

    fun refreshIfNeeded() {
        if (!RelayLicenseSupport.allowCoreRouting()) {
            return
        }
        if (!Data.configRelay.decision.enableNodeStatusAutoRefresh || RelayNodeRoutingSupport.configuredNodes().isEmpty()) {
            return
        }

        val now = System.currentTimeMillis()
        val intervalMillis = Data.configRelay.decision.nodeStatusRefreshSeconds.coerceIn(5, 300) * 1000L
        if (now - lastRefreshTimestamp < intervalMillis) {
            return
        }
        refreshAll(force = true)
    }

    fun refreshAll(force: Boolean = false) {
        if (!RelayLicenseSupport.allowCoreRouting()) {
            return
        }
        if (!Data.configRelay.decision.enableNodeStatusAutoRefresh || RelayNodeRoutingSupport.configuredNodes().isEmpty()) {
            return
        }

        val now = System.currentTimeMillis()
        val intervalMillis = Data.configRelay.decision.nodeStatusRefreshSeconds.coerceIn(5, 300) * 1000L
        if (!force && now - lastRefreshTimestamp < intervalMillis) {
            return
        }
        lastRefreshTimestamp = now

        RelayNodeRoutingSupport.configuredNodes().forEach { node ->
            refreshNode(node, now)
        }
    }

    fun observedState(code: String): RelayObservedNodeState? {
        return nodeStates[normalizeCode(code)]
    }

    fun freshState(code: String): RelayObservedNodeState? {
        val state = observedState(code) ?: return null
        if (!state.reachable) {
            return null
        }

        val staleMillis = Data.configRelay.decision.nodeStatusStaleSeconds.coerceIn(10, 600) * 1000L
        return if (System.currentTimeMillis() - state.lastSuccessTimestamp <= staleMillis) {
            state
        } else {
            null
        }
    }

    fun buildStatusUrl(node: BeanRelayNodeConfig): String? {
        node.statusApiUrl.trim().takeIf { it.isNotBlank() }?.let {
            return it
        }

        val host = when {
            node.domain.isNotBlank() -> node.domain.trim()
            node.ip.isNotBlank() -> node.ip.trim()
            else -> return null
        }
        if (node.webPort <= 0) {
            return null
        }

        val apiPath = node.statusApiPath.trim().ifBlank { Data.configRelayApi.statusApiPath }
        val normalizedPath = if (apiPath.startsWith("/")) apiPath else "/$apiPath"
        return "http://$host:${node.webPort}$normalizedPath"
    }

    private fun refreshNode(node: BeanRelayNodeConfig, now: Long) {
        val code = normalizeCode(node.code)
        val statusUrl = buildStatusUrl(node)
        if (statusUrl == null) {
            nodeStates[code] = RelayObservedNodeState(
                code = code,
                nodeName = node.displayName.ifBlank { node.name.ifBlank { code } },
                statusUrl = "",
                reachable = false,
                lastAttemptTimestamp = now,
                lastSuccessTimestamp = 0L,
                remoteUpdateTimestamp = 0L,
                roomAllSize = 0,
                roomPublicSize = 0,
                roomPrivateSize = 0,
                onlineSize = 0,
                tcpConnectionSize = 0,
                httpConnectionSize = 0,
                relayPlayerConnectionSize = 0,
                publicRoomHostIps = emptySet(),
                upListEnabled = node.allowPublicRoom,
                mainServer = false,
                localNodeCode = code,
                message = "status_api_not_configured"
            )
            return
        }

        val response = Data.core.rwHttp.doGet(statusUrl)
        if (response.isBlank()) {
            markFailure(node, code, statusUrl, now, "empty_response")
            return
        }

        try {
            val root = JSONObject(response)
            if (!root.optBoolean("ok", false)) {
                markFailure(node, code, statusUrl, now, "response_not_ok")
                return
            }

            val data = root.optJSONObject("data")
            if (data == null) {
                markFailure(node, code, statusUrl, now, "missing_data")
                return
            }

            nodeStates[code] = RelayObservedNodeState(
                code = code,
                nodeName = data.optString("nodeName", node.displayName.ifBlank { node.name.ifBlank { code } }),
                statusUrl = statusUrl,
                reachable = true,
                lastAttemptTimestamp = now,
                lastSuccessTimestamp = now,
                remoteUpdateTimestamp = data.optLong("updateTimestamp", now),
                roomAllSize = data.optInt("roomAllSize", 0),
                roomPublicSize = data.optInt("roomPublicSize", 0),
                roomPrivateSize = data.optInt("roomPrivateSize", 0),
                onlineSize = data.optInt("onlineSize", 0),
                tcpConnectionSize = data.optInt("tcpConnectionSize", 0),
                httpConnectionSize = data.optInt("httpConnectionSize", 0),
                relayPlayerConnectionSize = data.optInt("relayPlayerConnectionSize", 0),
                publicRoomHostIps = parsePublicRoomHostIps(data),
                upListEnabled = data.optBoolean("upListEnabled", node.allowPublicRoom),
                mainServer = data.optBoolean("mainServer", false),
                localNodeCode = data.optString("localNodeCode", code),
                message = "ok"
            )
        } catch (_: Exception) {
            markFailure(node, code, statusUrl, now, "invalid_json")
        }
    }

    private fun markFailure(node: BeanRelayNodeConfig, code: String, statusUrl: String, now: Long, message: String) {
        val previous = nodeStates[code]
        nodeStates[code] = RelayObservedNodeState(
            code = code,
            nodeName = previous?.nodeName ?: node.displayName.ifBlank { node.name.ifBlank { code } },
            statusUrl = statusUrl,
            reachable = false,
            lastAttemptTimestamp = now,
            lastSuccessTimestamp = previous?.lastSuccessTimestamp ?: 0L,
            remoteUpdateTimestamp = previous?.remoteUpdateTimestamp ?: 0L,
            roomAllSize = previous?.roomAllSize ?: 0,
            roomPublicSize = previous?.roomPublicSize ?: 0,
            roomPrivateSize = previous?.roomPrivateSize ?: 0,
            onlineSize = previous?.onlineSize ?: 0,
            tcpConnectionSize = previous?.tcpConnectionSize ?: 0,
            httpConnectionSize = previous?.httpConnectionSize ?: 0,
            relayPlayerConnectionSize = previous?.relayPlayerConnectionSize ?: 0,
            publicRoomHostIps = previous?.publicRoomHostIps ?: emptySet(),
            upListEnabled = previous?.upListEnabled ?: node.allowPublicRoom,
            mainServer = previous?.mainServer ?: false,
            localNodeCode = previous?.localNodeCode ?: code,
            message = message
        )
    }

    private fun parsePublicRoomHostIps(data: JSONObject): Set<String> {
        val result = linkedSetOf<String>()
        val hostIps = data.optJSONArray("publicRoomHostIps")
        if (hostIps != null) {
            for (index in 0 until hostIps.length()) {
                hostIps.optString(index).trim().takeIf { it.isNotBlank() }?.let(result::add)
            }
        }
        return result
    }

    private fun normalizeCode(code: String): String = code.trim().uppercase(Locale.ROOT)
}
