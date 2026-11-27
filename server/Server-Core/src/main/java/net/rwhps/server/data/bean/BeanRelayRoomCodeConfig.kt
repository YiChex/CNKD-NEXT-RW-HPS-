/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.data.bean

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.annotations.mark.PrivateMark
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.inline.toGson

data class BeanRelayRoomCodeScheme(
    val id: String = "RK",
    val prefix: String = "RK",
    val withNodeCode: Boolean = true,
    val enabled: Boolean = true
)

@PrivateMark
data class BeanRelayRoomCodeConfig(
    val enable: Boolean = true,
    val localNodeCode: String = "A",
    val suffixDigits: Int = 3,
    val primarySchemeId: String = "RK",
    val schemes: List<BeanRelayRoomCodeScheme> = listOf(
        BeanRelayRoomCodeScheme(id = "RK", prefix = "RK", withNodeCode = true, enabled = true),
        BeanRelayRoomCodeScheme(id = "OK", prefix = "OK", withNodeCode = false, enabled = false)
    )
): AbstractBeanConfig(
    this::class.java, "rwhps.config.relayRoomCode"
) {
    companion object {
        val fileUtils = FileUtils.getFolder(Data.ServerDataPath).toFile("RelayRoomCodeConfig.json")

        @JvmStatic
        fun stringToClass(): BeanRelayRoomCodeConfig {
            val raw = fileUtils.readFileStringData()
            val config = if (raw.isBlank()) {
                BeanRelayRoomCodeConfig()
            } else {
                BeanRelayRoomCodeConfig::class.java.toGson(raw)
            }
            config.bindFile(fileUtils)
            config.readProperty()
            if (raw.isBlank()) {
                config.save()
            }
            return config
        }
    }
}
