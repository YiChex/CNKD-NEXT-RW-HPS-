/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.data.bean

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import net.rwhps.server.util.inline.stringToGson
import net.rwhps.server.util.inline.toGson
import net.rwhps.server.util.inline.toPrettyPrintingJson

internal class BeanServerConfigFileTest {
    @Test
    fun normalizedClampsRuntimeMaxPlayerToGameCoreLimit() {
        val config = BeanServerConfigFile(
            gameplay = BeanServerConfigFile.GameplaySection(
                maxPlayer = 999,
                startMinPlayerSize = 200,
                autoStartMinPlayerSize = 300,
                autoStartCountdownSeconds = -5,
                defaultStartingCreditsAmount = -10
            )
        )

        val normalized = config.normalized()

        assertEquals(100, normalized.gameplay.maxPlayer)
        assertEquals(0, normalized.gameplay.startMinPlayerSize)
        assertEquals(100, normalized.gameplay.autoStartMinPlayerSize)
        assertEquals(0, normalized.gameplay.autoStartCountdownSeconds)
        assertEquals(0, normalized.gameplay.defaultStartingCreditsAmount)
        assertEquals(100, config.toServerConfig().maxPlayer)
    }

    @Test
    fun normalizedRaisesRuntimeMaxPlayerToMinimumSafeValue() {
        val config = BeanServerConfigFile(
            gameplay = BeanServerConfigFile.GameplaySection(
                maxPlayer = -10,
                autoStartMinPlayerSize = 4
            )
        )

        val normalized = config.normalized()

        assertEquals(1, normalized.gameplay.maxPlayer)
        assertEquals(1, normalized.gameplay.autoStartMinPlayerSize)
        assertEquals(1, config.toServerConfig().maxPlayer)
    }

    @Test
    fun normalizedClampsUnitLimitToSupportedRange() {
        val tooLow = BeanServerConfigFile(
            gameplay = BeanServerConfigFile.GameplaySection(maxUnit = 1)
        ).toServerConfig()
        val tooHigh = BeanServerConfigFile(
            gameplay = BeanServerConfigFile.GameplaySection(maxUnit = 50_000)
        ).toServerConfig()

        assertEquals(5, tooLow.maxUnit)
        assertEquals(5_000, tooHigh.maxUnit)
    }

    @Test
    fun defaultPlayerNoticesAndMessageLimitMatchPublicTemplate() {
        val gameplay = BeanServerConfigFile().gameplay

        assertEquals("欢迎加入本服务器", gameplay.enterAd)
        assertEquals("服务器已就绪！", gameplay.startAd)
        assertEquals("房间已达到管理员设置的人数上限 请稍后再试 @CNKD", gameplay.maxPlayerJoinAd)
        assertEquals("对局正在进行中 请稍后再试 @CNKD", gameplay.startPlayerJoinAd)
        assertEquals(100, gameplay.maxMessageLen)
    }

    @Test
    fun startModeDefaultsKeepLobbySyncSeparateFromLatencyHeartbeat() {
        val startMode = BeanServerConfigFile().toStartModeConfig()

        assertEquals(30_000, startMode.lobbyRefreshHeartbeatMillis)
        assertEquals(30_000, startMode.lobbyPacketHeartbeatMillis)
        assertEquals(1_000, startMode.latencyHeartbeatMillis)
        assertEquals(false, startMode.enableGameOverBattleRoomReturn)
        assertEquals(30, startMode.gameOverReturnCountdownSeconds)
        assertEquals(true, startMode.pruneDisconnectedPlayersOnGameOver)
    }

    @Test
    fun startModeRequiresExplicitGameOverBattleRoomReturnOptIn() {
        val missing = BeanServerConfigFile(
            lobby = BeanServerConfigFile.LobbySection(enableGameOverBattleRoomReturn = null)
        ).toStartModeConfig()
        val disabled = BeanServerConfigFile(
            lobby = BeanServerConfigFile.LobbySection(enableGameOverBattleRoomReturn = false)
        ).toStartModeConfig()
        val enabled = BeanServerConfigFile(
            lobby = BeanServerConfigFile.LobbySection(enableGameOverBattleRoomReturn = true)
        ).toStartModeConfig()

        assertEquals(false, missing.enableGameOverBattleRoomReturn)
        assertEquals(false, disabled.enableGameOverBattleRoomReturn)
        assertEquals(true, enabled.enableGameOverBattleRoomReturn)
    }

    @Test
    fun defeatedPlayerAutoKickStaysDisabledAndNormalizesItsDialogMessage() {
        val defaultConfig = BeanServerConfigFile().toStartModeConfig()
        val configured = BeanServerConfigFile(
            lobby = BeanServerConfigFile.LobbySection(
                enableAutoKickPlayerWhenAllUnitsDestroyed = true,
                autoKickPlayerWhenAllUnitsDestroyedMessage = "  \u0000全部单位已被摧毁  "
            )
        ).toStartModeConfig()

        assertEquals(false, defaultConfig.enableAutoKickPlayerWhenAllUnitsDestroyed)
        assertEquals(BeanServerConfigFile.DEFAULT_AUTO_KICK_DESTROYED_PLAYER_MESSAGE, defaultConfig.autoKickPlayerWhenAllUnitsDestroyedMessage)
        assertEquals(true, configured.enableAutoKickPlayerWhenAllUnitsDestroyed)
        assertEquals("全部单位已被摧毁", configured.autoKickPlayerWhenAllUnitsDestroyedMessage)
        assertTrue(BeanServerConfigFile().normalized().toPrettyPrintingJson().contains("\"enableAutoKickPlayerWhenAllUnitsDestroyed\": false"))
    }

    @Test
    fun legacyMissingDefeatedPlayerAutoKickFieldsAreWrittenBackAsSafeDefaults() {
        val parsed = BeanServerConfigFile::class.java.toGson("""{"lobby":{}}""")
        val normalized = parsed.normalized()

        assertEquals(false, normalized.lobby.enableAutoKickPlayerWhenAllUnitsDestroyed)
        assertEquals(BeanServerConfigFile.DEFAULT_AUTO_KICK_DESTROYED_PLAYER_MESSAGE, normalized.lobby.autoKickPlayerWhenAllUnitsDestroyedMessage)
    }

    @Test
    fun startModeClampsLobbySyncHeartbeatToLargeModSafeMinimum() {
        val startMode = BeanServerConfigFile(
            lobby = BeanServerConfigFile.LobbySection(
                lobbyRefreshHeartbeatMillis = 10_000,
                lobbyPacketHeartbeatMillis = 10_000,
                latencyHeartbeatMillis = 10_000
            )
        ).toStartModeConfig()

        assertEquals(30_000, startMode.lobbyRefreshHeartbeatMillis)
        assertEquals(30_000, startMode.lobbyPacketHeartbeatMillis)
        assertEquals(1_000, startMode.latencyHeartbeatMillis)
    }

    @Test
    fun startModeClampsGameOverReturnCountdown() {
        val tooLow = BeanServerConfigFile(
            lobby = BeanServerConfigFile.LobbySection(gameOverReturnCountdownSeconds = 1)
        ).toStartModeConfig()
        val tooHigh = BeanServerConfigFile(
            lobby = BeanServerConfigFile.LobbySection(gameOverReturnCountdownSeconds = 999)
        ).toStartModeConfig()

        assertEquals(5, tooLow.gameOverReturnCountdownSeconds)
        assertEquals(300, tooHigh.gameOverReturnCountdownSeconds)
    }

    @Test
    fun singleRoomLowLatencyDefaultsEnableForLegacyConfigs() {
        val server = BeanServerConfigFile().toServerConfig()

        assertEquals(true, server.singleRoomLowLatency.enabled)
        assertEquals(true, server.singleRoomLowLatency.fastGameCommand)
        assertEquals(true, server.singleRoomLowLatency.preTickGameCommandDrain)
        assertEquals(true, server.singleRoomLowLatency.preTickGameCommandDrainRequiresStartedRoom)
        assertEquals(256, server.singleRoomLowLatency.preTickGameCommandDrainLimit)
        assertEquals(false, server.singleRoomLowLatency.inlineGameCommandProcessing)
        assertEquals(10, server.singleRoomLowLatency.serverInjectedCommandLeadTicks)
        assertEquals(10, server.singleRoomLowLatency.hessOutboundCommandLeadTicks)
        // Frame/nest-step invariant guard must default ON: the engine defect it covers
        // (ad.s() zeroes ad.X but never l.bx) reproduces independently of the low-latency profile.
        assertEquals(true, server.singleRoomLowLatency.hessFrameInvariantGuardEnabled)
        assertEquals(0, server.singleRoomLowLatency.headlessFpsOverride)
        assertEquals(0, server.singleRoomLowLatency.nettyWorkerThreadsOverride)
        assertEquals(false, server.singleRoomLowLatency.directEventLoopWrites)
    }

    @Test
    fun singleRoomLowLatencyFrameInvariantGuardSurvivesLegacyConfigWithoutTheField() {
        // Legacy JSON predates hessFrameInvariantGuardEnabled; Gson uses the synthesized
        // all-defaults no-arg constructor, so the Kotlin default (true) must survive.
        val legacyJson = """
            {
              "singleRoomLowLatency": {
                "enabled": true,
                "metricsEnabled": false,
                "hessOutboundCommandLeadTicks": 8,
                "headlessFpsOverride": 120
              }
            }
        """.trimIndent()

        val server = stringToGson.fromJson(legacyJson, BeanServerConfigFile::class.java).toServerConfig()

        assertEquals(true, server.singleRoomLowLatency.enabled)
        assertEquals(8, server.singleRoomLowLatency.hessOutboundCommandLeadTicks)
        assertEquals(120, server.singleRoomLowLatency.headlessFpsOverride)
        assertEquals(true, server.singleRoomLowLatency.hessFrameInvariantGuardEnabled)
    }

    @Test
    fun singleRoomLowLatencyClampsRuntimeValues() {
        val server = BeanServerConfigFile(
            singleRoomLowLatency = BeanSingleRoomLowLatencyConfig(
                enabled = true,
                summaryIntervalSeconds = -1,
                preTickGameCommandDrainLimit = 99_999,
                serverInjectedCommandLeadTicks = 999,
                hessOutboundCommandLeadTicks = -1,
                headlessFpsOverride = 999,
                nettyWorkerThreadsOverride = 999,
                directEventLoopWrites = true
            )
        ).toServerConfig()

        assertEquals(true, server.singleRoomLowLatency.enabled)
        assertEquals(1, server.singleRoomLowLatency.summaryIntervalSeconds)
        assertEquals(4_096, server.singleRoomLowLatency.preTickGameCommandDrainLimit)
        assertEquals(60, server.singleRoomLowLatency.serverInjectedCommandLeadTicks)
        assertEquals(1, server.singleRoomLowLatency.hessOutboundCommandLeadTicks)
        assertEquals(240, server.singleRoomLowLatency.headlessFpsOverride)
        assertEquals(8, server.singleRoomLowLatency.nettyWorkerThreadsOverride)
        assertEquals(true, server.singleRoomLowLatency.directEventLoopWrites)
    }

    @Test
    fun toCoreConfigPreservesPublicListEntries() {
        val entry = BeanPublicListEntry(
            id = "main",
            enabled = true,
            serverName = "CNKD 5V5 团战娱乐排位",
            mapName = "10P自动化抽奖两岸",
            listNamePinnedCharacterCount = 10,
            listPinnedCharacterCount = 10,
            maxPlayerCount = 100
        )

        val core = BeanServerConfigFile(
            common = BeanServerConfigFile.CommonSection(publicListEntries = listOf(entry))
        ).toCoreConfig()

        assertEquals(1, core.publicListEntries.size)
        assertEquals(entry, core.publicListEntries.first())
    }

    @Test
    fun pluginLoggingDefaultsAreMappedToCoreConfig() {
        val logging = BeanServerConfigFile().toCoreConfig().pluginLogging

        assertEquals("INFO", logging.consoleLevel)
        assertEquals("INFO", logging.fileLevel)
        assertEquals(120, logging.infoLinesPerMinutePerPlugin)
        assertEquals(2_048, logging.maxMessageLength)
        assertEquals(8, logging.maxFileSizeMb)
        assertEquals(7, logging.retainDays)
    }

    @Test
    fun coreErrorLogRetentionIsMappedAndBounded() {
        val defaults = BeanServerConfigFile().toCoreConfig()
        val bounded = BeanServerConfigFile(
            common = BeanServerConfigFile.CommonSection(
                errorLogMaxSizeMb = 0,
                errorLogRetainFiles = 99
            )
        ).normalized().toCoreConfig()

        assertEquals(8, defaults.errorLogMaxSizeMb)
        assertEquals(3, defaults.errorLogRetainFiles)
        assertEquals(1, bounded.errorLogMaxSizeMb)
        assertEquals(10, bounded.errorLogRetainFiles)
    }

    @Test
    fun pluginLoggingNormalizesLevelsAndBounds() {
        val logging = BeanServerConfigFile(
            pluginLogging = BeanPluginLoggingConfig(
                consoleLevel = "warn",
                fileLevel = "unknown",
                infoLinesPerMinutePerPlugin = -1,
                maxMessageLength = 99_999,
                maxFileSizeMb = 0,
                retainDays = 999
            )
        ).toCoreConfig().pluginLogging

        assertEquals("WARN", logging.consoleLevel)
        assertEquals("INFO", logging.fileLevel)
        assertEquals(10, logging.infoLinesPerMinutePerPlugin)
        assertEquals(8_192, logging.maxMessageLength)
        assertEquals(1, logging.maxFileSizeMb)
        assertEquals(30, logging.retainDays)
    }

    @Test
    fun pluginLoggingMissingOrNullIsRepairedForLegacyConfig() {
        val missing = BeanServerConfigFile().normalized().pluginLogging!!
        val normalized = BeanServerConfigFile(pluginLogging = null).normalized()
        val logging = normalized.pluginLogging!!

        assertEquals("INFO", missing.consoleLevel)
        assertEquals("INFO", logging.consoleLevel)
        assertEquals("INFO", logging.fileLevel)
        assertEquals(120, logging.infoLinesPerMinutePerPlugin)
    }

    @Test
    fun pluginLoggingIsWrittenWhenLegacyJsonDoesNotContainTheField() {
        val parsed = BeanServerConfigFile::class.java.toGson("""{"common":{"log":"WARN"}}""")
        val rewritten = parsed.normalized().toPrettyPrintingJson()

        assertEquals(null, parsed.pluginLogging)
        assertTrue(rewritten.contains("\"pluginLogging\""))
        assertTrue(rewritten.contains("\"consoleLevel\": \"INFO\""))
    }
}
