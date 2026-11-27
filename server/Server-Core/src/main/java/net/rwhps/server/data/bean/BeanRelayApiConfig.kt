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

@PrivateMark
data class BeanRelayApiConfig(
    val statusPagePath: String = "/relay/info",
    val statusApiPath: String = "/relay/status",
    val roomsApiPath: String = "/relay/rooms",
    val adminApiPath: String = "/relay/admin",
    val enableStatusPage: Boolean = true,
    val enableStatusApi: Boolean = true,
    val enableRoomsApi: Boolean = true,
    val enableAdminApi: Boolean = false,
    val statusApiRequireWhitelist: Boolean = true,
    val statusApiIpWhitelist: String = "127.0.0.1,::1",
    val roomsApiRequireWhitelist: Boolean = false,
    val roomsApiIpWhitelist: String = "",
    val adminApiRequireWhitelist: Boolean = true,
    val adminApiIpWhitelist: String = "127.0.0.1,::1",
    val adminApiToken: String = "",
    val corsEnable: Boolean = true,
    val corsAllowAllOrigins: Boolean = true,
    val corsAllowOrigins: String = "*",
    val corsAllowCredentials: Boolean = false,
    val corsAllowMethods: String = "GET, POST, OPTIONS",
    val corsAllowHeaders: String = "Content-Type, Origin, Accept, Authorization, X-Relay-Admin-Token",
    val corsMaxAgeSeconds: Int = 600,
    val statusApiPublicRoomLimit: Int = 20,
    val statusApiCacheMillis: Int = 2000,
    val roomsApiCacheMillis: Int = 3000,
    val apiRateLimitEnabled: Boolean = true,
    val statusApiRateLimitPerMinute: Int = 120,
    val roomsApiRateLimitPerMinute: Int = 60,
    val adminApiRateLimitPerMinute: Int = 30,
    val roomsApiIncludeClosed: Boolean = false,
    val roomsApiLimit: Int = 1000
): AbstractBeanConfig(
    this::class.java, "rwhps.config.relayApi"
) {
    companion object {
        val fileUtils = FileUtils.getFolder(Data.ServerDataPath).toFile("RelayApiConfig.json")

        @JvmStatic
        fun stringToClass(): BeanRelayApiConfig {
            val config: BeanRelayApiConfig = BeanRelayApiConfig::class.java.toGson(fileUtils.readFileStringData())
            config.bindFile(fileUtils)
            config.readProperty()
            return config
        }
    }
}
