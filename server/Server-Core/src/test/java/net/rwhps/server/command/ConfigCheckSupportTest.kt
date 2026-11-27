/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.command

import net.rwhps.server.data.bean.BeanPublicListEntry
import net.rwhps.server.data.bean.BeanRelayApiConfig
import net.rwhps.server.data.bean.BeanRelayLicenseConfig
import net.rwhps.server.data.bean.BeanServerConfigFile
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ConfigCheckSupportTest {
    @Test
    fun buildReportForDetectsHighRiskSettings() {
        val report = ConfigCheckSupport.buildReportFor(
            serverConfig = BeanServerConfigFile(
                common = BeanServerConfigFile.CommonSection(autoUpList = true),
                network = BeanServerConfigFile.NetworkSection(webPort = 8080, webToken = "RW-HPS"),
                map = BeanServerConfigFile.MapSection(enableMapLock = true, lockMap = "")
            ),
            relayApiConfig = BeanRelayApiConfig(
                enableAdminApi = true,
                adminApiToken = "",
                apiRateLimitEnabled = false,
                roomsApiLimit = 6000
            ),
            relayLicenseConfig = BeanRelayLicenseConfig(licenseKey = "")
        )
        val text = report.joinToString("\n")

        assertTrue(text.contains("map.lockMap"))
        assertTrue(text.contains("adminApiToken"))
        assertTrue(text.contains("API 限流已关闭"))
        assertTrue(text.contains("licenseKey"))
    }

    @Test
    fun buildReportExplainsForcedResetUnlessBattleRoomReturnIsExplicitlyEnabled() {
        val defaultReport = ConfigCheckSupport.buildReportFor(
            serverConfig = BeanServerConfigFile()
        ).joinToString("\n")
        val enabledReport = ConfigCheckSupport.buildReportFor(
            serverConfig = BeanServerConfigFile(
                lobby = BeanServerConfigFile.LobbySection(enableGameOverBattleRoomReturn = true)
            )
        ).joinToString("\n")

        assertTrue(defaultReport.contains("赛后将提示强制重置"))
        assertTrue(defaultReport.contains("不会自动返回战役室"))
        assertFalse(enabledReport.contains("赛后将提示强制重置"))
    }

    @Test
    fun buildReportForAcceptsValidPublicListEntries() {
        val report = ConfigCheckSupport.buildReportFor(
            serverConfig = BeanServerConfigFile(
                common = BeanServerConfigFile.CommonSection(
                    autoUpList = true,
                    publicListEntries = listOf(
                        BeanPublicListEntry(
                            id = "main",
                            serverName = "CNKD 5V5 团战娱乐排位",
                            mapName = "10P自动化抽奖两岸",
                            listNamePinnedCharacterCount = 10,
                            listPinnedCharacterCount = 10,
                            maxPlayerCount = 100
                        ),
                        BeanPublicListEntry(
                            id = "newbie",
                            serverName = "CNKD 新手友好 5V5",
                            mapName = "新手团战入口",
                            listNamePinnedCharacterCount = 6,
                            listPinnedCharacterCount = 6,
                            maxPlayerCount = 100
                        )
                    )
                )
            ),
            relayLicenseConfig = BeanRelayLicenseConfig(licenseKey = "test-license")
        )
        val text = report.joinToString("\n")

        assertFalse(text.contains("publicListEntries"))
    }

    @Test
    fun buildReportForWarnsInvalidPublicListEntries() {
        val report = ConfigCheckSupport.buildReportFor(
            serverConfig = BeanServerConfigFile(
                common = BeanServerConfigFile.CommonSection(
                    publicListEntries = listOf(
                        BeanPublicListEntry(id = "main", serverName = "A"),
                        BeanPublicListEntry(id = "main", serverName = "B"),
                        BeanPublicListEntry(id = "Bad Id", serverName = "C"),
                        BeanPublicListEntry(
                            id = "long_name",
                            serverName = "x".repeat(81),
                            listNamePinnedCharacterCount = 65,
                            listPinnedCharacterCount = -1,
                            maxPlayerCount = 300
                        ),
                        BeanPublicListEntry(id = "entry4", serverName = "D"),
                        BeanPublicListEntry(id = "entry5", serverName = "E"),
                        BeanPublicListEntry(id = "entry6", serverName = "F"),
                        BeanPublicListEntry(id = "entry7", serverName = "G"),
                        BeanPublicListEntry(id = "entry8", serverName = "H")
                    )
                )
            )
        )
        val text = report.joinToString("\n")

        assertTrue(text.contains("实际上传会限制为前 8 条"))
        assertTrue(text.contains("与前面的入口重复"))
        assertTrue(text.contains("不合法"))
        assertTrue(text.contains("serverName 超过 80 字符"))
        assertTrue(text.contains("置顶字符建议保持 0-64"))
        assertTrue(text.contains("maxPlayerCount 建议保持 1-200"))
    }
}
