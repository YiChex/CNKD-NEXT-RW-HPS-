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

@PrivateMark
data class BeanRelayLicenseConfig(
    val leaseEndpoint: String = "",
    val licenseKey: String = "",
    val instanceName: String = "",
    val customerHint: String = "",
    val publicIpv4Hint: String = "",
    val requestTimeoutSeconds: Int = 8,
    val refreshSeconds: Int = 30 * 60,
    val graceHours: Int = 72,
    val requestedFeatures: List<String> = listOf(
        "relay_basic",
        "uplist",
        "relay_server_mode",
        "core_routing",
        "custom_room",
        "rooms_api",
        "admin_api"
    )
): AbstractBeanConfig(
    this::class.java, "rwhps.config.relayLicense"
) {
    companion object {
        val fileUtils = FileUtils.getFolder(Data.ServerDataPath).toFile("RelayLicenseConfig.json")

        @JvmStatic
        fun stringToClass(): BeanRelayLicenseConfig {
            val raw = fileUtils.readFileStringData()
            val config = if (raw.isBlank()) {
                BeanRelayLicenseConfig()
            } else {
                BeanRelayLicenseConfig::class.java.toGson(raw)
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
