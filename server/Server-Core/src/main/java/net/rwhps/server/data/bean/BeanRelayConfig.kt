/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.data.bean

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.annotations.mark.PrivateMark
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.inline.toGson

data class BeanRelayMonitoringConfig(
    val pagePath: String = "/relay/info",
    val apiPath: String = "/relay/status",
    val apiEnable: Boolean = true,
    val apiIpWhitelist: String = "127.0.0.1,::1",
    val apiPublicRoomLimit: Int = 20
)

data class BeanRelaySelfConfig(
    val displayName: String = "CNKD 上海节点",
    val shortName: String = "CNKD",
    val region: String = "中国上海",
    val operatorName: String = "CNKD团队",
    val qqGroup: String = "",
    val joinBaseUrl: String = "",
    val shareRoomNodeCode: String = "A",
    val listOpenText: String = "支持列表开放",
    val entryPopupTemplate: String = "",
    val entryNotice: String = "",
    val roomNotice: String = "",
    val hostNotice: String = "",
    val roomStatusNoticeTemplate: String = "",
    val relayServerStatusNoticeTemplate: String = ""
)

data class BeanRelayDecisionConfig(
    val enableSmartPublicRoomDispatch: Boolean = true,
    val fallbackToCoreWhenNoNodeAvailable: Boolean = true,
    val enableNodeStatusAutoRefresh: Boolean = true,
    val nodeStatusRefreshSeconds: Int = 15,
    val nodeStatusStaleSeconds: Int = 45,
    /** Advertised max player count on the public list for relay nodes. Does not change actual room logic. */
    val uplistAdvertisedMaxPlayerSize: Int = 520,
    val reserveServerListSlot: Boolean = true,
    val userPublicRoomLimitPerNode: Int = 3,
    val enableSinglePublicRoomPerIpLimit: Boolean = true,
    val recyclePublicRoomOnStartGame: Boolean = true,
    val publicRoomSuccessCheckSeconds: Int = 2,
    val relayRoomHostOnlyTimeoutSeconds: Int = 15 * 60,
    val relayRoomStartedInactiveTimeoutSeconds: Int = 30 * 60,
    val enableRelayRoomRecycle: Boolean = true,
    val relayRoomRecycleCheckSeconds: Int = 60,
    val relayRoomInactiveTimeoutSeconds: Int = 12 * 60 * 60
)

data class BeanRelayNodeConfig(
    val code: String = "",
    val name: String = "",
    val domain: String = "",
    val ip: String = "",
    val port: Int = 5123,
    val webPort: Int = 0,
    val statusApiUrl: String = "",
    val statusApiPath: String = "/relay/status",
    val displayName: String = "",
    val shortName: String = "",
    val region: String = "",
    val operatorName: String = "",
    val qqGroup: String = "",
    val joinBaseUrl: String = "",
    val listOpenText: String = "",
    val uplistLimit: Int = 4,
    val uplistUsed: Int = 0,
    val publicRoomBias: Int = 0,
    val enabled: Boolean = true,
    val allowCreate: Boolean = true,
    val allowPublicRoom: Boolean = true
)

/**
 * Relay-Protocol configuration file
 *
 * Save data for serialization and deserialization
 * @author Dr (dr@der.kim)
 */
@PrivateMark
data class BeanRelayConfig(
    val mainID: String = "D",
    val mainServer: Boolean = true,
    val upList: Boolean = true,
    val mainServerIP: String = "relay.der.kim",
    val mainServerPort: Int = 4993,
    /** Current node code. Leave blank on the core node, set like `A` on child nodes. */
    val localNodeCode: String = "",
    /**
     * Multi-node redirect rules for the core relay.
     *
     * Format:
     * `A=relay-a.example.com:5123;B=relay-b.example.com:5123`
     *
     * When a player enters a room id with the `R` + node-code prefix,
     * for example `RA12345`, the core relay can redirect the client to
     * the target endpoint configured for node `A`.
     */
    val relayRedirectRules: String = "",
    /** Core relay status page path */
    val relayStatusPagePath: String = "/relay/info",
    /** Pure JSON status API path for external monitoring */
    val relayStatusApiPath: String = "/relay/status",
    /** Enable the pure JSON status API */
    val relayStatusApiEnable: Boolean = true,
    /** Comma/newline/semicolon separated IP whitelist for the JSON status API */
    val relayStatusApiIpWhitelist: String = "127.0.0.1,::1",
    /** Maximum number of public rooms returned by the JSON status API */
    val relayStatusApiPublicRoomLimit: Int = 20,
    /** Preferred JSON config block for monitoring */
    val monitoring: BeanRelayMonitoringConfig = BeanRelayMonitoringConfig(),
    /** Decision options for the core relay */
    val decision: BeanRelayDecisionConfig = BeanRelayDecisionConfig(),
    /** Core/child node definitions used by smart dispatch and monitoring */
    val nodes: List<BeanRelayNodeConfig> = emptyList(),
    /** Current deployment branding and entry settings */
    val self: BeanRelaySelfConfig = BeanRelaySelfConfig()
): AbstractBeanConfig(
        this::class.java, "rwhps.config.relay"
) {

    companion object {
        val fileUtils = FileUtils.getFolder(Data.ServerDataPath).toFile("RelayConfig.json")

        @JvmStatic
        fun stringToClass(): BeanRelayConfig {

            val config: BeanRelayConfig = BeanRelayConfig::class.java.toGson(fileUtils.readFileStringData())
            config.bindFile(fileUtils)
            config.readProperty()
            return config
        }
    }
}
