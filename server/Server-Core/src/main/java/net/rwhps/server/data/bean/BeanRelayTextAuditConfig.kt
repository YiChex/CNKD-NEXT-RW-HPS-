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
data class BeanRelayTextAuditConfig(
    val enable: Boolean = false,
    val providerName: String = "ET",
    val endpoint: String = "",
    val headers: Map<String, String> = emptyMap(),
    val requestTemplate: String = """{"text":{text_json},"field":{field_json},"provider":{provider_json},"scene":"relay_public_room"}""",
    val responseAllowPath: String = "",
    val responseAllowValues: String = "true,allow,pass,ok",
    val responseBlockPath: String = "",
    val responseBlockValues: String = "false,block,reject,deny",
    val responseTextPath: String = "",
    val rejectFallbackText: String = "[内容审核中]",
    val allowOnError: Boolean = true,
    val denyPublicRoomWhenRejected: Boolean = false,
    val cacheSeconds: Int = 300
): AbstractBeanConfig(
    this::class.java, "rwhps.config.relayTextAudit"
) {
    companion object {
        val fileUtils = FileUtils.getFolder(Data.ServerDataPath).toFile("RelayTextAuditConfig.json")

        @JvmStatic
        fun stringToClass(): BeanRelayTextAuditConfig {
            val config: BeanRelayTextAuditConfig = BeanRelayTextAuditConfig::class.java.toGson(fileUtils.readFileStringData())
            config.bindFile(fileUtils)
            config.readProperty()
            return config
        }
    }
}
