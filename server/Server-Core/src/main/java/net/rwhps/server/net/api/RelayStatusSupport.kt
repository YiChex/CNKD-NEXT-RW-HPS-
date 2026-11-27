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
import net.rwhps.server.game.room.RelayRoom
import net.rwhps.server.data.global.Data
import net.rwhps.server.data.global.NetStaticData
import net.rwhps.server.net.NetService
import net.rwhps.server.plugin.internal.headless.support.StartModeAdvertisingRedirectSupport
import net.rwhps.server.util.SystemUtils
import net.rwhps.server.util.Time
import java.util.concurrent.atomic.AtomicInteger

internal object RelayStatusSupport {
    data class PublicRoomView(
        val roomId: String,
        val displayId: String,
        val lookupId: String,
        val playerSize: Int,
        val status: String,
        val hostName: String,
        val mapName: String,
        val customRoom: Boolean,
        val publicPriority: Int,
        val isMod: Boolean,
        val publicTopTime: Int,
        val lastActivityTime: Int,
        val joinLink: String?
    )

    data class RoomView(
        val roomId: String,
        val displayId: String,
        val lookupId: String,
        val playerSize: Int,
        val activeConnectionSize: Int,
        val status: String,
        val hostName: String,
        val mapName: String,
        val customRoom: Boolean,
        val publicPriority: Int,
        val publicRoom: Boolean,
        val closeRoom: Boolean,
        val isMod: Boolean,
        val roomCreateTime: Int,
        val publicTopTime: Int,
        val lastActivityTime: Int,
        val joinLink: String?
    )

    data class RelayNodeView(
        val code: String,
        val name: String,
        val domain: String,
        val ip: String,
        val port: Int,
        val webPort: Int,
        val statusApiUrl: String,
        val displayName: String,
        val shortName: String,
        val region: String,
        val operatorName: String,
        val qqGroup: String,
        val joinBaseUrl: String,
        val listOpenText: String,
        val uplistLimit: Int,
        val uplistUsed: Int,
        val uplistRemaining: Int,
        val userPublicRoomLimit: Int,
        val userPublicRoomUsed: Int,
        val userPublicRoomRemaining: Int,
        val publicRoomBias: Int,
        val enabled: Boolean,
        val allowCreate: Boolean,
        val allowPublicRoom: Boolean,
        val observedHealthy: Boolean,
        val observedRoomAllSize: Int?,
        val observedRoomPublicSize: Int?,
        val observedOnlineSize: Int?,
        val observedTcpConnectionSize: Int?,
        val observedRelayPlayerConnectionSize: Int?,
        val observedLastSuccessTimestamp: Long?,
        val observedMessage: String
    )

    data class RelayStatusView(
        val nodeName: String,
        val region: String,
        val operatorName: String,
        val listOpen: Boolean,
        val qqGroup: String,
        val joinBaseUrl: String,
        val listOpenText: String,
        val serverVersion: String,
        val roomAllSize: Int,
        val roomNoStartSize: Int,
        val roomPublicSize: Int,
        val roomPrivateSize: Int,
        val onlineSize: Int,
        val tcpConnectionSize: Int,
        val httpConnectionSize: Int,
        val relayPlayerConnectionSize: Int,
        val javaHeapMb: Long,
        val javaTotalMemoryMb: Long,
        val javaFreeMemoryMb: Long,
        val javaVendor: String,
        val javaVersion: String,
        val osName: String,
        val updateTime: String,
        val updateTimestamp: Long,
        val mainId: String,
        val mainServer: Boolean,
        val localNodeCode: String,
        val upListEnabled: Boolean,
        val redirectRules: String,
        val monitoringPagePath: String,
        val monitoringApiPath: String,
        val smartDispatchEnabled: Boolean,
        val advertisingRedirect: StartModeAdvertisingRedirectSupport.AdvertisingRedirectRuntimeSnapshot,
        val warnings: List<String>,
        val license: RelayLicenseRuntimeStatus,
        val textAudit: RelayTextAuditRuntimeStatus,
        val publicRooms: List<PublicRoomView>,
        val publicRoomHostIps: List<String>,
        val nodes: List<RelayNodeView>
    )

    data class RelayRoomsView(
        val nodeName: String,
        val role: String,
        val localNodeCode: String,
        val roomCount: Int,
        val roomNoStartSize: Int,
        val roomPublicSize: Int,
        val roomPrivateSize: Int,
        val updateTime: String,
        val updateTimestamp: Long,
        val rooms: List<RoomView>
    )

    fun buildStatus(limit: Int = statusApiPublicRoomLimit()): RelayStatusView {
        RelayNodeRuntimeSupport.refreshIfNeeded()
        val onlineSize = AtomicInteger()
        val tcpConnectionSize = AtomicInteger()
        val httpConnectionSize = AtomicInteger()
        NetStaticData.netService.eachAll { e: NetService ->
            val size = e.getConnectSize()
            onlineSize.addAndGet(size)
            when (e.netType) {
                NetService.Companion.NetTypeEnum.HTTPNet -> httpConnectionSize.addAndGet(size)
                else -> tcpConnectionSize.addAndGet(size)
            }
        }

        val safeLimit = limit.coerceIn(1, 100)
        val publicRooms = RelayRoom.getPublicRoomSnapshots(safeLimit).map {
            PublicRoomView(
                roomId = it.roomId,
                displayId = RelayDeploymentSupport.roomDisplayId(it.roomId),
                lookupId = RelayDeploymentSupport.roomLookupId(it.roomId),
                playerSize = it.playerSize,
                status = if (it.started) "ingame" else "battleroom",
                hostName = it.hostName,
                mapName = it.mapName,
                customRoom = it.customRoom,
                publicPriority = it.publicPriority,
                isMod = it.isMod,
                publicTopTime = it.publicTopTime,
                lastActivityTime = it.lastActivityTime,
                joinLink = RelayDeploymentSupport.roomJoinLink(it.roomId)
            )
        }
        val nodes = RelayNodeRoutingSupport.configuredNodes().map(::toNodeView)
        val textAudit = RelayTextAuditSupport.runtimeStatus()
        val license = RelayLicenseSupport.runtimeStatus()
        val advertisingRedirect = StartModeAdvertisingRedirectSupport.runtimeSnapshot()
        val warnings = buildWarnings(nodes, textAudit, license, advertisingRedirect)

        return RelayStatusView(
            nodeName = RelayDeploymentSupport.displayName(),
            region = RelayDeploymentSupport.region(),
            operatorName = RelayDeploymentSupport.operatorName(),
            listOpen = Data.configRelay.upList,
            qqGroup = RelayDeploymentSupport.qqGroup(),
            joinBaseUrl = RelayDeploymentSupport.joinBaseUrl(),
            listOpenText = RelayDeploymentSupport.listOpenText(),
            serverVersion = Data.SERVER_CORE_VERSION,
            roomAllSize = RelayRoom.roomAllSize,
            roomNoStartSize = RelayRoom.roomNoStartSize,
            roomPublicSize = RelayRoom.roomPublicSize,
            roomPrivateSize = RelayRoom.roomNoStartSize - RelayRoom.roomPublicSize,
            onlineSize = onlineSize.get(),
            tcpConnectionSize = tcpConnectionSize.get(),
            httpConnectionSize = httpConnectionSize.get(),
            relayPlayerConnectionSize = RelayRoom.allSize,
            javaHeapMb = SystemUtils.javaHeap / (1024 * 1024),
            javaTotalMemoryMb = SystemUtils.javaTotalMemory / (1024 * 1024),
            javaFreeMemoryMb = SystemUtils.javaFreeMemory / (1024 * 1024),
            javaVendor = SystemUtils.javaVendor,
            javaVersion = SystemUtils.javaVersion,
            osName = SystemUtils.osName,
            updateTime = Time.getMilliFormat(1),
            updateTimestamp = System.currentTimeMillis(),
            mainId = Data.configRelay.mainID,
            mainServer = Data.configRelay.mainServer,
            localNodeCode = Data.configRelay.localNodeCode,
            upListEnabled = Data.configRelay.upList,
            redirectRules = Data.configRelay.relayRedirectRules,
            monitoringPagePath = statusPagePath(),
            monitoringApiPath = statusApiPath(),
            smartDispatchEnabled = Data.configRelay.decision.enableSmartPublicRoomDispatch,
            advertisingRedirect = advertisingRedirect,
            warnings = warnings,
            license = license,
            textAudit = textAudit,
            publicRooms = publicRooms,
            publicRoomHostIps = RelayRoom.getPublicRoomHostIps(),
            nodes = nodes
        )
    }

    fun renderPublicRoomsHtml(limit: Int = statusApiPublicRoomLimit()): String {
        val publicRooms = buildStatus(limit).publicRooms
        if (publicRooms.isEmpty()) {
            return "<p>暂无公开房间</p>"
        }
        return publicRooms.joinToString(
            separator = "<br/>",
            prefix = "<p>",
            postfix = "</p>"
        ) {
            buildString {
                append("${it.displayId} / 玩家: ${it.playerSize} / 状态: ${it.status} / MOD: ${it.isMod}")
                if (it.customRoom) {
                    append(" / 定制房间")
                }
                if (!it.joinLink.isNullOrBlank()) {
                    append(" / 入口: ${it.joinLink}")
                }
            }
        }
    }

    fun buildRooms(includeClosed: Boolean = Data.configRelayApi.roomsApiIncludeClosed, limit: Int = Data.configRelayApi.roomsApiLimit): RelayRoomsView {
        val safeLimit = limit.coerceIn(1, 10000)
        val rooms = RelayRoom.getAllRoomSnapshots(safeLimit, includeClosed).map {
            RoomView(
                roomId = it.roomId,
                displayId = RelayDeploymentSupport.roomDisplayId(it.roomId),
                lookupId = RelayDeploymentSupport.roomLookupId(it.roomId),
                playerSize = it.playerSize,
                activeConnectionSize = it.activeConnectionSize,
                status = when {
                    it.closeRoom -> "closed"
                    it.started -> "ingame"
                    else -> "battleroom"
                },
                hostName = it.hostName,
                mapName = it.mapName,
                customRoom = it.customRoom,
                publicPriority = it.publicPriority,
                publicRoom = it.publicRoom,
                closeRoom = it.closeRoom,
                isMod = it.isMod,
                roomCreateTime = it.roomCreateTime,
                publicTopTime = it.publicTopTime,
                lastActivityTime = it.lastActivityTime,
                joinLink = RelayDeploymentSupport.roomJoinLink(it.roomId)
            )
        }

        return RelayRoomsView(
            nodeName = RelayDeploymentSupport.displayName(),
            role = RelayDeploymentSupport.roleName(),
            localNodeCode = RelayDeploymentSupport.localNodeCode(),
            roomCount = RelayRoom.roomAllSize,
            roomNoStartSize = RelayRoom.roomNoStartSize,
            roomPublicSize = RelayRoom.roomPublicSize,
            roomPrivateSize = RelayRoom.roomNoStartSize - RelayRoom.roomPublicSize,
            updateTime = Time.getMilliFormat(1),
            updateTimestamp = System.currentTimeMillis(),
            rooms = rooms
        )
    }

    fun statusPagePath(): String = Data.configRelayApi.statusPagePath

    fun statusApiPath(): String = Data.configRelayApi.statusApiPath

    fun statusApiEnabled(): Boolean = Data.configRelayApi.enableStatusApi

    fun statusPageEnabled(): Boolean = Data.configRelayApi.enableStatusPage

    fun statusApiPublicRoomLimit(): Int {
        return Data.configRelayApi.statusApiPublicRoomLimit.takeIf { it > 0 } ?: 20
    }

    private fun buildWarnings(
        nodes: List<RelayNodeView>,
        textAudit: RelayTextAuditRuntimeStatus,
        license: RelayLicenseRuntimeStatus,
        advertisingRedirect: StartModeAdvertisingRedirectSupport.AdvertisingRedirectRuntimeSnapshot
    ): List<String> {
        val warnings = linkedSetOf<String>()

        if (!license.configured) {
            warnings += "授权配置尚未填写，授权功能当前不可用"
        } else if (!license.active && !license.withinGrace) {
            warnings += "当前节点授权无效，已进入功能降级状态"
        } else if (license.withinGrace) {
            warnings += "当前节点授权处于离线宽限期，请尽快恢复与授权站的连接"
        }

        if (Data.configRelayApi.enableAdminApi) {
            val adminToken = Data.configRelayApi.adminApiToken.trim()
            if (adminToken.isBlank()) {
                warnings += "管理员接口已启用，但 adminApiToken 为空"
            } else if (adminToken == "PLEASE_CHANGE_ME") {
                warnings += "管理员接口仍在使用示例 Token，请立即改成你自己的密钥"
            }

            if (Data.configRelayApi.adminApiRequireWhitelist &&
                Data.configRelayApi.adminApiIpWhitelist.split(',', ';', '\n', '\r').none { it.trim().isNotEmpty() }
            ) {
                warnings += "管理员接口启用了白名单，但 adminApiIpWhitelist 为空"
            }
        }

        if (Data.configRelayApi.enableStatusApi &&
            Data.configRelayApi.statusApiRequireWhitelist &&
            Data.configRelayApi.statusApiIpWhitelist.split(',', ';', '\n', '\r').none { it.trim().isNotEmpty() }
        ) {
            warnings += "状态接口启用了白名单，但 statusApiIpWhitelist 为空"
        }

        if (Data.configRelayApi.enableRoomsApi && !Data.configRelayApi.roomsApiRequireWhitelist && Data.configRelayApi.corsAllowAllOrigins) {
            warnings += "房间接口当前允许任意来源直接抓取全部房间状态"
        }

        if (textAudit.enabled && !textAudit.endpointConfigured) {
            warnings += "文本审核已启用，但审核接口 endpoint 未配置"
        }

        if (Data.configRelay.mainServer && Data.configRelay.decision.enableNodeStatusAutoRefresh && nodes.isEmpty()) {
            warnings += "当前实例是核心服，但没有配置任何子节点"
        }

        nodes.filter { it.statusApiUrl.isBlank() }.forEach {
            warnings += "节点 ${it.code} 未配置状态接口地址，核心服无法实时采集它的运行状态"
        }

        nodes.filter { !it.observedHealthy && it.observedMessage.isNotBlank() }.forEach {
            warnings += "节点 ${it.code} 状态采集异常: ${it.observedMessage}"
        }

        if (advertisingRedirect.enabled && !advertisingRedirect.targetValid) {
            warnings += "广告房跳转已启用，但目标地址无效"
        }

        if (advertisingRedirect.enabled &&
            advertisingRedirect.health.checkedAtMillis > 0L &&
            !advertisingRedirect.health.ok
        ) {
            warnings += "广告房最近一次目标探测失败: ${advertisingRedirect.health.message}"
        }

        return warnings.toList()
    }

    private fun toNodeView(node: BeanRelayNodeConfig): RelayNodeView {
        val observedState = RelayNodeRuntimeSupport.observedState(node.code)
        return RelayNodeView(
            code = node.code,
            name = node.name,
            domain = node.domain,
            ip = node.ip,
            port = node.port,
            webPort = node.webPort,
            statusApiUrl = RelayNodeRuntimeSupport.buildStatusUrl(node) ?: "",
            displayName = node.displayName,
            shortName = node.shortName,
            region = node.region,
            operatorName = node.operatorName,
            qqGroup = node.qqGroup,
            joinBaseUrl = node.joinBaseUrl,
            listOpenText = node.listOpenText,
            uplistLimit = node.uplistLimit,
            uplistUsed = node.uplistUsed,
            uplistRemaining = RelayNodeRoutingSupport.uplistRemaining(node),
            userPublicRoomLimit = RelayNodeRoutingSupport.userPublicRoomLimit(node),
            userPublicRoomUsed = RelayNodeRoutingSupport.observedPublicRoomUsed(node),
            userPublicRoomRemaining = RelayNodeRoutingSupport.effectiveUserPublicRoomRemaining(node),
            publicRoomBias = node.publicRoomBias,
            enabled = node.enabled,
            allowCreate = node.allowCreate,
            allowPublicRoom = node.allowPublicRoom,
            observedHealthy = observedState?.reachable == true && RelayNodeRuntimeSupport.freshState(node.code) != null,
            observedRoomAllSize = observedState?.roomAllSize,
            observedRoomPublicSize = observedState?.roomPublicSize,
            observedOnlineSize = observedState?.onlineSize,
            observedTcpConnectionSize = observedState?.tcpConnectionSize,
            observedRelayPlayerConnectionSize = observedState?.relayPlayerConnectionSize,
            observedLastSuccessTimestamp = observedState?.lastSuccessTimestamp?.takeIf { it > 0L },
            observedMessage = observedState?.message ?: ""
        )
    }
}
