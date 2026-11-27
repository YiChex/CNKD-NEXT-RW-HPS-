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

data class BeanRelayCustomRoomDefinition(
    val roomId: String = "",
    val enabled: Boolean = true,
    val nodeCode: String = "",
    val isMod: Boolean = false,
    val autoPublicRoom: Boolean = true,
    val forcePublicRoom: Boolean = true,
    val publicPriority: Int = 1000,
    val joinBaseUrl: String = "",
    val connectHost: String = "",
    val connectPort: Int = 0,
    val entryNotice: String = "",
    val roomWelcome: String = "",
    val roomChatNotice: String = "",
    val hostChatNotice: String = "",
    val chatSender: String = "",
    val maxPlayerSize: Int = -1,
    val maxUnitSize: Int = 200,
    val income: Float = 1f
)

data class BeanRelayServerModeConfig(
    val enable: Boolean = false,
    val roomCount: Int = 4,
    val publicPorts: List<Int> = listOf(5201, 5202, 5203, 5204),
    val roomIdPrefix: String = "RS",
    val roomIdDigits: Int = 5,
    val isMod: Boolean = false,
    val publicPriority: Int = 4000,
    val defaultListName: String = "",
    val defaultMapName: String = "RELAY-CN 自定义房间",
    val hostClaimNotice: String = "",
    val roomWelcome: String = "",
    val roomChatNotice: String = "",
    val hostChatNotice: String = "",
    val chatSender: String = "RELAY-CN",
    val joinBaseUrl: String = "",
    val connectHost: String = "",
    val connectPort: Int = 0,
    val maxPlayerSize: Int = 50,
    val maxUnitSize: Int = 200,
    val income: Float = 1f,
    val enableAutoAdjustMaxPlayer: Boolean = true,
    val autoAdjustMaxPlayerMin: Int = 4,
    val autoAdjustMaxPlayerMax: Int = 50,
    val listUpdateSeconds: Int = 60
)

@PrivateMark
data class BeanRelayCustomRoomConfig(
    val enable: Boolean = true,
    val hotReloadSeconds: Int = 5,
    val relayServerMode: BeanRelayServerModeConfig = BeanRelayServerModeConfig(),
    val rooms: List<BeanRelayCustomRoomDefinition> = emptyList()
): AbstractBeanConfig(
    this::class.java, "rwhps.config.relayCustomRoom"
) {
    companion object {
        val fileUtils = FileUtils.getFolder(Data.ServerDataPath).toFile("RelayCustomRoomConfig.json")

        @JvmStatic
        fun stringToClass(): BeanRelayCustomRoomConfig {
            val config: BeanRelayCustomRoomConfig = BeanRelayCustomRoomConfig::class.java.toGson(fileUtils.readFileStringData())
            config.bindFile(fileUtils)
            config.readProperty()
            return config
        }
    }
}
