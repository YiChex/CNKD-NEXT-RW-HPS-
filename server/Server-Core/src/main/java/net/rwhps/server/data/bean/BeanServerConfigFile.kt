/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.data.bean

data class BeanServerConfigFile(
    val common: CommonSection = CommonSection(),
    val network: NetworkSection = NetworkSection(),
    val gameplay: GameplaySection = GameplaySection(),
    val lobby: LobbySection = LobbySection(),
    val map: MapSection = MapSection(),
    val ranked: RankedSection = RankedSection(),
    val advertisingRedirect: AdvertisingRedirectSection = AdvertisingRedirectSection(),
    val console: BeanConsoleConfig = BeanConsoleConfig(),
    val pluginLogging: BeanPluginLoggingConfig? = null,
    val singleRoomLowLatency: BeanSingleRoomLowLatencyConfig = BeanSingleRoomLowLatencyConfig()
) {
    data class CommonSection(
        val defStartCommand: String = "start",
        val log: String = "WARN",
        val errorLogMaxSizeMb: Int = 8,
        val errorLogRetainFiles: Int = 3,
        val cmdTitle: String = "",
        val followBetaVersion: Boolean = false,
        val port: Int = 5123,
        val serverName: String = "CNKD NEXT Start 服",
        val chatSenderName: String? = "CNKD",
        val subtitle: String = "",
        val autoUpList: Boolean = false,
        val listStatusSuffixEnabled: Boolean = false,
        val listNamePinnedCharacterCount: Int = 0,
        val listPinnedCharacterCount: Int = 0,
        val publicListEntries: List<BeanPublicListEntry> = emptyList(),
        val listExperimentEnabled: Boolean = false,
        val listExperimentGameName: String = "",
        val listExperimentCreatedBy: String = "",
        val listExperimentMapName: String = "",
        val listExperimentGameStatus: String = "",
        val listExperimentPlayerCount: Int = -1,
        val listExperimentMaxPlayerCount: Int = -1,
        val listPromotionModeEnabled: Boolean? = false,
        val listPromotionGameStatus: String? = "chat",
        val listPromotionPlayerCount: Int? = -1,
        val listPromotionMaxPlayerCount: Int? = -1,
        val listPromotionSortBoostEnabled: Boolean? = false,
        val listPromotionSortBoostPrefix: String? = "\uD808\uDC31",
        val listPromotionBlankMapEnabled: Boolean? = false
    )

    data class NetworkSection(
        val ipCheckMultiLanguageSupport: Boolean = true,
        val singleUserRelay: Boolean = false,
        val singleUserRelayMod: Boolean = false,
        val mixProtocolEnable: Boolean = false,
        val rconMixEnable: Boolean = false,
        val webMixEnable: Boolean = false,
        val rconPort: Int = 0,
        val rconPasswd: String = "RW-HPS",
        val webPort: Int = 0,
        val webToken: String = "RW-HPS",
        val webHOST: String = "",
        val sslEnable: Boolean = false,
        val sslMixEnable: Boolean = false,
        val sslPasswd: String = "RW-HPS"
    )

    data class GameplaySection(
        val serverID: String = "CNKD-NEXT",
        val enterAd: String = "欢迎加入本服务器",
        val startAd: String = "服务器已就绪！",
        val maxPlayerJoinAd: String = "房间已达到管理员设置的人数上限 请稍后再试 @CNKD",
        val startPlayerJoinAd: String = "对局正在进行中 请稍后再试 @CNKD",
        val passwd: String = "",
        val maxPlayer: Int = 100,
        val oneAdmin: Boolean = true,
        val startMinPlayerSize: Int = -1,
        val autoStartMinPlayerSize: Int = -1,
        val autoStartCountdownSeconds: Int = 5,
        val manualStartCountdownSeconds: Int = 5,
        val maxGameIngTime: Int = 7200,
        val maxOnlyAIGameIngTime: Int = 3600,
        val enableAI: Boolean = false,
        val maxMessageLen: Int = 100,
        val maxUnit: Int = 1000,
        val defIncome: Float = 1f,
        val defaultStartingCreditsAmount: Int = 4000,
        /** New-lobby no-nukes default. A current host selection has higher pre-start priority. */
        val disableNukes: Boolean = false,
        val defaultFogMode: String = "lineOfSight",
        val enableDenseFog: Boolean? = null,
        val isAfk: Boolean = true,
        val muteAll: Boolean = false,
        val turnStoneIntoGold: Boolean = false,
        val modsLoadErrorPrint: Boolean = false,
        val saveRePlayFile: Boolean = true
    )

    data class LobbySection(
        val disableTeamChange: Boolean = false,
        val disablePositionChange: Boolean = false,
        val adminCanOverrideTeamRules: Boolean = true,
        val adminCanOverridePositionRules: Boolean = true,
        val enableFreeForAllMode: Boolean = false,
        val enableAlternateTeamsOnJoin: Boolean = false,
        val enableSurvivalMapMode: Boolean = false,
        val survivalGameOverGraceSeconds: Int? = 180,
        val survivalEnemyClearConfirmSeconds: Int? = 45,
        val enableLobbySyncOptimization: Boolean? = true,
        val lobbyRefreshHeartbeatMillis: Int? = 30_000,
        val lobbyPacketHeartbeatMillis: Int? = 30_000,
        val latencyHeartbeatMillis: Int? = 1_000,
        val enableLobbyGameSyncPacketFilter: Boolean? = true,
        val lobbyGameSyncPacketHeartbeatMillis: Int? = 3_000,
        val lobbyGameSyncPacketJoinGraceMillis: Int? = 180_000,
        val reconnectGraceMillis: Int? = 5_000,
        val enableGameOverBattleRoomReturn: Boolean? = false,
        val gameOverReturnCountdownSeconds: Int? = 30,
        val pruneDisconnectedPlayersOnGameOver: Boolean? = true,
        val enableJoinReadinessTips: Boolean? = false,
        val joinTipModWarningThreshold: Int? = 1,
        val enableIndividualSurrender: Boolean? = false,
        val enableAutoKickPlayerWhenAllUnitsDestroyed: Boolean? = false,
        val autoKickPlayerWhenAllUnitsDestroyedMessage: String? = BeanServerConfigFile.DEFAULT_AUTO_KICK_DESTROYED_PLAYER_MESSAGE
    )

    data class MapSection(
        val enableMapLock: Boolean = false,
        val lockMap: String = "",
        val enableModPackageMapDiscovery: Boolean? = true,
        val modPackageMapScanMaxArchiveSizeMb: Int? = 512,
        val enableModPackageMapSupportExtraction: Boolean? = true,
        val enableModPackageMapAutoExtraction: Boolean? = true,
        val enableModPackageMapRuntimeGc: Boolean? = true,
        val modPackageMapRuntimeGcGraceHours: Int? = 24,
        val modPackageMapRuntimeGcMaxDeletePerRun: Int? = 20
    )

    data class RankedSection(
        val coreNodeEnabled: Boolean = false,
        val gatewayBaseUrl: String = "http://127.0.0.1:3010",
        val queueCode: String = "ranked_1v1",
        val pollIntervalSeconds: Int = 2,
        val jumpDelaySeconds: Int = 1
    )

    data class AdvertisingRedirectSection(
        val enabled: Boolean = false,
        val targetHost: String = "101.43.41.22",
        val targetPort: Int = 5123,
        val targetRoomId: String = "",
        val redirectTiming: String = "afterJoin",
        val joinProbeSafeMode: Boolean = true,
        val delaySeconds: Int = 2,
        val showNotice: Boolean = true,
        val noticeBeforeRedirect: String = "欢迎来到 CNKD 导航服 正在为你跳转到主服",
        val noticeOnManualFallback: String = "如未自动跳转 请手动加入 {host}:{port}",
        val kickAfterRedirect: Boolean = true,
        val cooldownSecondsPerPlayer: Int = 10,
        val maxRedirectAttemptsPerConnection: Int = 1,
        val allowLocalhostTarget: Boolean = false,
        val allowPrivateIpTarget: Boolean = true,
        val allowedTargetHosts: List<String> = emptyList(),
        val disableWhenRankedCoreNode: Boolean = true,
        val disableWhenRankedShardNode: Boolean = true,
        val adminBypass: Boolean = true,
        val logRedirect: Boolean = true
    )

    fun toCoreConfig(runPid: Long = 0L): BeanCoreConfig {
        return BeanCoreConfig(
            defStartCommand = common.defStartCommand,
            log = common.log,
            errorLogMaxSizeMb = common.errorLogMaxSizeMb,
            errorLogRetainFiles = common.errorLogRetainFiles,
            cmdTitle = common.cmdTitle,
            followBetaVersion = common.followBetaVersion,
            port = common.port,
            serverName = common.serverName,
            chatSenderName = normalizeChatSenderName(common.chatSenderName),
            subtitle = common.subtitle,
            autoUpList = common.autoUpList,
            listStatusSuffixEnabled = common.listStatusSuffixEnabled,
            listNamePinnedCharacterCount = common.listNamePinnedCharacterCount,
            listPinnedCharacterCount = common.listPinnedCharacterCount,
            publicListEntries = common.publicListEntries.orEmpty(),
            listExperimentEnabled = common.listExperimentEnabled,
            listExperimentGameName = common.listExperimentGameName,
            listExperimentCreatedBy = common.listExperimentCreatedBy,
            listExperimentMapName = common.listExperimentMapName,
            listExperimentGameStatus = common.listExperimentGameStatus,
            listExperimentPlayerCount = common.listExperimentPlayerCount,
            listExperimentMaxPlayerCount = common.listExperimentMaxPlayerCount,
            listPromotionModeEnabled = common.listPromotionModeEnabled == true,
            listPromotionGameStatus = common.listPromotionGameStatus?.takeIf { it.isNotBlank() } ?: "chat",
            listPromotionPlayerCount = common.listPromotionPlayerCount ?: -1,
            listPromotionMaxPlayerCount = common.listPromotionMaxPlayerCount ?: -1,
            listPromotionSortBoostEnabled = common.listPromotionSortBoostEnabled == true,
            listPromotionSortBoostPrefix = common.listPromotionSortBoostPrefix?.takeIf { it.isNotBlank() } ?: "\uD808\uDC31",
            listPromotionBlankMapEnabled = common.listPromotionBlankMapEnabled == true,
            console = console.normalized(),
            pluginLogging = (pluginLogging ?: BeanPluginLoggingConfig()).normalized(),
            ipCheckMultiLanguageSupport = network.ipCheckMultiLanguageSupport,
            singleUserRelay = network.singleUserRelay,
            singleUserRelayMod = network.singleUserRelayMod,
            mixProtocolEnable = network.mixProtocolEnable,
            rconMixEnable = network.rconMixEnable,
            webMixEnable = network.webMixEnable,
            rconPort = network.rconPort,
            rconPasswd = network.rconPasswd,
            webPort = network.webPort,
            webToken = network.webToken,
            webHOST = network.webHOST,
            sslEnable = network.sslEnable,
            sslMixEnable = network.sslMixEnable,
            sslPasswd = network.sslPasswd,
            runPid = runPid
        )
    }

    fun normalized(): BeanServerConfigFile {
        val normalizedMaxPlayer = gameplay.maxPlayer.coerceIn(1, GAME_MAX_PLAYER_SIZE)
        var normalizedStartMinPlayerSize = gameplay.startMinPlayerSize.coerceAtLeast(-1)
        var normalizedAutoStartMinPlayerSize = gameplay.autoStartMinPlayerSize.coerceAtLeast(-1)

        if (normalizedStartMinPlayerSize > normalizedMaxPlayer) {
            normalizedStartMinPlayerSize = 0
        }
        if (normalizedAutoStartMinPlayerSize != -1 && normalizedAutoStartMinPlayerSize > normalizedMaxPlayer) {
            normalizedAutoStartMinPlayerSize = normalizedMaxPlayer
        }
        if (
            normalizedStartMinPlayerSize != -1 &&
            normalizedAutoStartMinPlayerSize != -1 &&
            normalizedAutoStartMinPlayerSize < normalizedStartMinPlayerSize
        ) {
            normalizedAutoStartMinPlayerSize = normalizedStartMinPlayerSize
        }

        val normalizedCommon = common.copy(
            chatSenderName = normalizeChatSenderName(common.chatSenderName),
            errorLogMaxSizeMb = common.errorLogMaxSizeMb.coerceIn(1, 64),
            errorLogRetainFiles = common.errorLogRetainFiles.coerceIn(0, 10)
        )
        val normalizedGameplay = gameplay.copy(
            maxPlayer = normalizedMaxPlayer,
            maxUnit = gameplay.maxUnit.coerceIn(GAME_MIN_UNIT_LIMIT, GAME_MAX_UNIT_LIMIT),
            startMinPlayerSize = normalizedStartMinPlayerSize,
            autoStartMinPlayerSize = normalizedAutoStartMinPlayerSize,
            autoStartCountdownSeconds = gameplay.autoStartCountdownSeconds.coerceAtLeast(0),
            manualStartCountdownSeconds = gameplay.manualStartCountdownSeconds.coerceIn(0, 300),
            defaultStartingCreditsAmount = gameplay.defaultStartingCreditsAmount.coerceAtLeast(0),
            defaultFogMode = normalizeFogMode(gameplay.defaultFogMode, gameplay.enableDenseFog)
        )
        val normalizedLobby = lobby.copy(
            enableAutoKickPlayerWhenAllUnitsDestroyed = lobby.enableAutoKickPlayerWhenAllUnitsDestroyed == true,
            autoKickPlayerWhenAllUnitsDestroyedMessage = normalizeAutoKickDestroyedPlayerMessage(
                lobby.autoKickPlayerWhenAllUnitsDestroyedMessage
            )
        )
        val normalizedAdvertisingRedirect = advertisingRedirect.copy(
            targetHost = advertisingRedirect.targetHost.trim(),
            targetRoomId = advertisingRedirect.targetRoomId.trim(),
            redirectTiming = "afterJoin",
            delaySeconds = advertisingRedirect.delaySeconds.coerceIn(0, 30),
            cooldownSecondsPerPlayer = advertisingRedirect.cooldownSecondsPerPlayer.coerceIn(0, 600),
            maxRedirectAttemptsPerConnection = advertisingRedirect.maxRedirectAttemptsPerConnection.coerceAtLeast(1),
            allowedTargetHosts = advertisingRedirect.allowedTargetHosts.map { it.trim() }.filter { it.isNotBlank() }
        )
        val normalizedConsole = console.normalized()
        val normalizedPluginLogging = (pluginLogging ?: BeanPluginLoggingConfig()).normalized()
        val normalizedSingleRoomLowLatency = singleRoomLowLatency.normalized()

        return if (
            normalizedCommon == common &&
            normalizedGameplay == gameplay &&
            normalizedLobby == lobby &&
            normalizedAdvertisingRedirect == advertisingRedirect &&
            normalizedConsole == console &&
            normalizedPluginLogging == pluginLogging &&
            normalizedSingleRoomLowLatency == singleRoomLowLatency
        ) {
            this
        } else {
            copy(
                common = normalizedCommon,
                gameplay = normalizedGameplay,
                lobby = normalizedLobby,
                advertisingRedirect = normalizedAdvertisingRedirect,
                console = normalizedConsole,
                pluginLogging = normalizedPluginLogging,
                singleRoomLowLatency = normalizedSingleRoomLowLatency
            )
        }
    }

    fun toServerConfig(): BeanServerConfig {
        val normalized = normalized()
        val gameplay = normalized.gameplay
        return BeanServerConfig(
            serverID = gameplay.serverID,
            enterAd = gameplay.enterAd,
            startAd = gameplay.startAd,
            maxPlayerJoinAd = gameplay.maxPlayerJoinAd,
            startPlayerJoinAd = gameplay.startPlayerJoinAd,
            passwd = gameplay.passwd,
            maxPlayer = gameplay.maxPlayer,
            oneAdmin = gameplay.oneAdmin,
            startMinPlayerSize = gameplay.startMinPlayerSize,
            autoStartMinPlayerSize = gameplay.autoStartMinPlayerSize,
            autoStartCountdownSeconds = gameplay.autoStartCountdownSeconds,
            manualStartCountdownSeconds = gameplay.manualStartCountdownSeconds,
            maxGameIngTime = gameplay.maxGameIngTime,
            maxOnlyAIGameIngTime = gameplay.maxOnlyAIGameIngTime,
            enableAI = gameplay.enableAI,
            maxMessageLen = gameplay.maxMessageLen,
            maxUnit = gameplay.maxUnit,
            defIncome = gameplay.defIncome,
            defaultStartingCreditsAmount = gameplay.defaultStartingCreditsAmount,
            disableNukes = gameplay.disableNukes,
            defaultFogMode = normalizeFogMode(gameplay.defaultFogMode, gameplay.enableDenseFog),
            isAfk = gameplay.isAfk,
            muteAll = gameplay.muteAll,
            turnStoneIntoGold = gameplay.turnStoneIntoGold,
            modsLoadErrorPrint = gameplay.modsLoadErrorPrint,
            saveRePlayFile = gameplay.saveRePlayFile,
            singleRoomLowLatency = normalized.singleRoomLowLatency
        )
    }

    fun toStartModeConfig(): BeanStartModeConfig {
        return BeanStartModeConfig(
            enableMapLock = map.enableMapLock,
            lockMap = map.lockMap,
            enableSurvivalMapMode = lobby.enableSurvivalMapMode,
            survivalGameOverGraceSeconds = (lobby.survivalGameOverGraceSeconds ?: 180).coerceIn(0, 900),
            survivalEnemyClearConfirmSeconds = (lobby.survivalEnemyClearConfirmSeconds ?: 45).coerceIn(0, 300),
            disableTeamChange = lobby.disableTeamChange,
            disablePositionChange = lobby.disablePositionChange,
            adminCanOverrideTeamRules = lobby.adminCanOverrideTeamRules,
            adminCanOverridePositionRules = lobby.adminCanOverridePositionRules,
            enableFreeForAllMode = lobby.enableFreeForAllMode,
            enableAlternateTeamsOnJoin = lobby.enableAlternateTeamsOnJoin,
            enableLobbySyncOptimization = lobby.enableLobbySyncOptimization != false,
            lobbyRefreshHeartbeatMillis = (lobby.lobbyRefreshHeartbeatMillis ?: 30_000).coerceIn(30_000, 120_000),
            lobbyPacketHeartbeatMillis = (lobby.lobbyPacketHeartbeatMillis ?: 30_000).coerceIn(30_000, 120_000),
            latencyHeartbeatMillis = normalizeLatencyHeartbeatMillis(lobby.latencyHeartbeatMillis),
            enableLobbyGameSyncPacketFilter = lobby.enableLobbyGameSyncPacketFilter != false,
            lobbyGameSyncPacketHeartbeatMillis = (lobby.lobbyGameSyncPacketHeartbeatMillis ?: 3_000).coerceIn(500, 120_000),
            lobbyGameSyncPacketJoinGraceMillis = (lobby.lobbyGameSyncPacketJoinGraceMillis ?: 180_000).coerceIn(0, 600_000),
            reconnectGraceMillis = (lobby.reconnectGraceMillis ?: 5_000).coerceIn(0, 60_000),
            enableGameOverBattleRoomReturn = lobby.enableGameOverBattleRoomReturn == true,
            gameOverReturnCountdownSeconds = (lobby.gameOverReturnCountdownSeconds ?: 30).coerceIn(5, 300),
            pruneDisconnectedPlayersOnGameOver = lobby.pruneDisconnectedPlayersOnGameOver != false,
            enableJoinReadinessTips = lobby.enableJoinReadinessTips == true,
            joinTipModWarningThreshold = (lobby.joinTipModWarningThreshold ?: 1).coerceIn(0, 1000),
            enableAutoKickPlayerWhenAllUnitsDestroyed = lobby.enableAutoKickPlayerWhenAllUnitsDestroyed == true,
            autoKickPlayerWhenAllUnitsDestroyedMessage = normalizeAutoKickDestroyedPlayerMessage(
                lobby.autoKickPlayerWhenAllUnitsDestroyedMessage
            ),
            enableModPackageMapDiscovery = map.enableModPackageMapDiscovery != false,
            modPackageMapScanMaxArchiveSizeMb = (map.modPackageMapScanMaxArchiveSizeMb ?: 512).coerceIn(0, 4096),
            enableModPackageMapSupportExtraction = map.enableModPackageMapSupportExtraction != false,
            enableModPackageMapAutoExtraction = map.enableModPackageMapAutoExtraction != false,
            enableModPackageMapRuntimeGc = map.enableModPackageMapRuntimeGc != false,
            modPackageMapRuntimeGcGraceHours = (map.modPackageMapRuntimeGcGraceHours ?: 24).coerceIn(0, 8760),
            modPackageMapRuntimeGcMaxDeletePerRun = (map.modPackageMapRuntimeGcMaxDeletePerRun ?: 20).coerceIn(1, 1000),
            rankedCoreNodeEnabled = ranked.coreNodeEnabled,
            rankedGatewayBaseUrl = ranked.gatewayBaseUrl,
            rankedQueueCode = ranked.queueCode,
            rankedPollIntervalSeconds = ranked.pollIntervalSeconds,
            rankedJumpDelaySeconds = ranked.jumpDelaySeconds,
            advertisingRedirectEnabled = advertisingRedirect.enabled,
            advertisingRedirectTargetHost = advertisingRedirect.targetHost,
            advertisingRedirectTargetPort = advertisingRedirect.targetPort,
            advertisingRedirectTargetRoomId = advertisingRedirect.targetRoomId,
            advertisingRedirectTiming = advertisingRedirect.redirectTiming,
            advertisingRedirectJoinProbeSafeMode = advertisingRedirect.joinProbeSafeMode,
            advertisingRedirectDelaySeconds = advertisingRedirect.delaySeconds,
            advertisingRedirectShowNotice = advertisingRedirect.showNotice,
            advertisingRedirectNoticeBeforeRedirect = advertisingRedirect.noticeBeforeRedirect,
            advertisingRedirectNoticeOnManualFallback = advertisingRedirect.noticeOnManualFallback,
            advertisingRedirectKickAfterRedirect = advertisingRedirect.kickAfterRedirect,
            advertisingRedirectCooldownSecondsPerPlayer = advertisingRedirect.cooldownSecondsPerPlayer,
            advertisingRedirectMaxAttemptsPerConnection = advertisingRedirect.maxRedirectAttemptsPerConnection,
            advertisingRedirectAllowLocalhostTarget = advertisingRedirect.allowLocalhostTarget,
            advertisingRedirectAllowPrivateIpTarget = advertisingRedirect.allowPrivateIpTarget,
            advertisingRedirectAllowedTargetHosts = advertisingRedirect.allowedTargetHosts,
            advertisingRedirectDisableWhenRankedCoreNode = advertisingRedirect.disableWhenRankedCoreNode,
            advertisingRedirectDisableWhenRankedShardNode = advertisingRedirect.disableWhenRankedShardNode,
            advertisingRedirectAdminBypass = advertisingRedirect.adminBypass,
            advertisingRedirectLogRedirect = advertisingRedirect.logRedirect,
            enableIndividualSurrender = lobby.enableIndividualSurrender == true
        )
    }

    companion object {
        const val GAME_MAX_PLAYER_SIZE = 100
        const val GAME_MIN_UNIT_LIMIT = 5
        const val GAME_MAX_UNIT_LIMIT = 5_000
        private const val DEFAULT_CHAT_SENDER_NAME = "CNKD"
        private const val CHAT_SENDER_NAME_MAX_LENGTH = 32
        const val DEFAULT_AUTO_KICK_DESTROYED_PLAYER_MESSAGE = "你已失去全部单位 已被服务器移出本局"
        private const val AUTO_KICK_DESTROYED_PLAYER_MESSAGE_MAX_LENGTH = 256

        fun normalizeChatSenderName(rawValue: String?): String {
            val cleaned = rawValue
                .orEmpty()
                .filterNot { Character.isISOControl(it) }
                .trim()
            return cleaned.ifBlank { DEFAULT_CHAT_SENDER_NAME }.take(CHAT_SENDER_NAME_MAX_LENGTH)
        }

        fun normalizeAutoKickDestroyedPlayerMessage(rawValue: String?): String {
            val cleaned = rawValue
                .orEmpty()
                .filterNot { Character.isISOControl(it) }
                .trim()
            return cleaned
                .ifBlank { DEFAULT_AUTO_KICK_DESTROYED_PLAYER_MESSAGE }
                .take(AUTO_KICK_DESTROYED_PLAYER_MESSAGE_MAX_LENGTH)
        }

        private fun normalizeFogMode(rawValue: String, legacyEnableDenseFog: Boolean?): String {
            return when (rawValue.trim().lowercase()) {
                "off", "0", "false" -> "off"
                "basic", "1" -> "basic"
                "lineofsight", "line_of_sight", "line-of-sight", "lineofsightfog", "2", "true" -> "lineOfSight"
                else -> if (legacyEnableDenseFog == true) "lineOfSight" else "off"
            }
        }

        private fun normalizeLatencyHeartbeatMillis(rawValue: Int?): Int {
            val value = rawValue ?: 1_000
            return if (value == 10_000) {
                // 10s was the previous default; keep direct JAR replacement on the new 1s behavior.
                1_000
            } else {
                value.coerceIn(1_000, 60_000)
            }
        }

        fun fromCurrent(
            core: BeanCoreConfig,
            server: BeanServerConfig,
            startMode: BeanStartModeConfig
        ): BeanServerConfigFile {
            return BeanServerConfigFile(
                common = CommonSection(
                    defStartCommand = core.defStartCommand,
                    log = core.log,
                    errorLogMaxSizeMb = core.errorLogMaxSizeMb,
                    errorLogRetainFiles = core.errorLogRetainFiles,
                    cmdTitle = core.cmdTitle,
                    followBetaVersion = core.followBetaVersion,
                    port = core.port,
                    serverName = core.serverName,
                    chatSenderName = normalizeChatSenderName(core.chatSenderName),
                    subtitle = core.subtitle,
                    autoUpList = core.autoUpList,
                    listStatusSuffixEnabled = core.listStatusSuffixEnabled,
                    listNamePinnedCharacterCount = core.listNamePinnedCharacterCount,
                    listPinnedCharacterCount = core.listPinnedCharacterCount,
                    publicListEntries = core.publicListEntries.orEmpty(),
                    listExperimentEnabled = core.listExperimentEnabled,
                    listExperimentGameName = core.listExperimentGameName,
                    listExperimentCreatedBy = core.listExperimentCreatedBy,
                    listExperimentMapName = core.listExperimentMapName,
                    listExperimentGameStatus = core.listExperimentGameStatus,
                    listExperimentPlayerCount = core.listExperimentPlayerCount,
                    listExperimentMaxPlayerCount = core.listExperimentMaxPlayerCount,
                    listPromotionModeEnabled = core.listPromotionModeEnabled,
                    listPromotionGameStatus = core.listPromotionGameStatus,
                    listPromotionPlayerCount = core.listPromotionPlayerCount,
                    listPromotionMaxPlayerCount = core.listPromotionMaxPlayerCount,
                    listPromotionSortBoostEnabled = core.listPromotionSortBoostEnabled,
                    listPromotionSortBoostPrefix = core.listPromotionSortBoostPrefix,
                    listPromotionBlankMapEnabled = core.listPromotionBlankMapEnabled
                ),
                network = NetworkSection(
                    ipCheckMultiLanguageSupport = core.ipCheckMultiLanguageSupport,
                    singleUserRelay = core.singleUserRelay,
                    singleUserRelayMod = core.singleUserRelayMod,
                    mixProtocolEnable = core.mixProtocolEnable,
                    rconMixEnable = core.rconMixEnable,
                    webMixEnable = core.webMixEnable,
                    rconPort = core.rconPort,
                    rconPasswd = core.rconPasswd,
                    webPort = core.webPort,
                    webToken = core.webToken,
                    webHOST = core.webHOST,
                    sslEnable = core.sslEnable,
                    sslMixEnable = core.sslMixEnable,
                    sslPasswd = core.sslPasswd
                ),
                gameplay = GameplaySection(
                    serverID = server.serverID,
                    enterAd = server.enterAd,
                    startAd = server.startAd,
                    maxPlayerJoinAd = server.maxPlayerJoinAd,
                    startPlayerJoinAd = server.startPlayerJoinAd,
                    passwd = server.passwd,
                    maxPlayer = server.maxPlayer,
                    oneAdmin = server.oneAdmin,
                    startMinPlayerSize = server.startMinPlayerSize,
                    autoStartMinPlayerSize = server.autoStartMinPlayerSize,
                    autoStartCountdownSeconds = server.autoStartCountdownSeconds,
                    manualStartCountdownSeconds = server.manualStartCountdownSeconds,
                    maxGameIngTime = server.maxGameIngTime,
                    maxOnlyAIGameIngTime = server.maxOnlyAIGameIngTime,
                    enableAI = server.enableAI,
                    maxMessageLen = server.maxMessageLen,
                    maxUnit = server.maxUnit,
                    defIncome = server.defIncome,
                    defaultStartingCreditsAmount = server.defaultStartingCreditsAmount,
                    disableNukes = server.disableNukes,
                    defaultFogMode = server.defaultFogMode,
                    isAfk = server.isAfk,
                    muteAll = server.muteAll,
                    turnStoneIntoGold = server.turnStoneIntoGold,
                    modsLoadErrorPrint = server.modsLoadErrorPrint,
                    saveRePlayFile = server.saveRePlayFile
                ),
                lobby = LobbySection(
                    disableTeamChange = startMode.disableTeamChange,
                    disablePositionChange = startMode.disablePositionChange,
                    adminCanOverrideTeamRules = startMode.adminCanOverrideTeamRules,
                    adminCanOverridePositionRules = startMode.adminCanOverridePositionRules,
                    enableFreeForAllMode = startMode.enableFreeForAllMode,
                    enableAlternateTeamsOnJoin = startMode.enableAlternateTeamsOnJoin,
                    enableSurvivalMapMode = startMode.enableSurvivalMapMode,
                    survivalGameOverGraceSeconds = startMode.survivalGameOverGraceSeconds,
                    survivalEnemyClearConfirmSeconds = startMode.survivalEnemyClearConfirmSeconds,
                    enableLobbySyncOptimization = startMode.enableLobbySyncOptimization,
                    lobbyRefreshHeartbeatMillis = startMode.lobbyRefreshHeartbeatMillis,
                    lobbyPacketHeartbeatMillis = startMode.lobbyPacketHeartbeatMillis,
                    latencyHeartbeatMillis = startMode.latencyHeartbeatMillis,
                    enableLobbyGameSyncPacketFilter = startMode.enableLobbyGameSyncPacketFilter,
                    lobbyGameSyncPacketHeartbeatMillis = startMode.lobbyGameSyncPacketHeartbeatMillis,
                    lobbyGameSyncPacketJoinGraceMillis = startMode.lobbyGameSyncPacketJoinGraceMillis,
                    reconnectGraceMillis = startMode.reconnectGraceMillis,
                    enableGameOverBattleRoomReturn = startMode.enableGameOverBattleRoomReturn,
                    gameOverReturnCountdownSeconds = startMode.gameOverReturnCountdownSeconds,
                    pruneDisconnectedPlayersOnGameOver = startMode.pruneDisconnectedPlayersOnGameOver,
                    enableJoinReadinessTips = startMode.enableJoinReadinessTips,
                    joinTipModWarningThreshold = startMode.joinTipModWarningThreshold,
                    enableIndividualSurrender = startMode.enableIndividualSurrender,
                    enableAutoKickPlayerWhenAllUnitsDestroyed = startMode.enableAutoKickPlayerWhenAllUnitsDestroyed,
                    autoKickPlayerWhenAllUnitsDestroyedMessage = normalizeAutoKickDestroyedPlayerMessage(
                        startMode.autoKickPlayerWhenAllUnitsDestroyedMessage
                    )
                ),
                map = MapSection(
                    enableMapLock = startMode.enableMapLock,
                    lockMap = startMode.lockMap,
                    enableModPackageMapDiscovery = startMode.enableModPackageMapDiscovery,
                    modPackageMapScanMaxArchiveSizeMb = startMode.modPackageMapScanMaxArchiveSizeMb,
                    enableModPackageMapSupportExtraction = startMode.enableModPackageMapSupportExtraction,
                    enableModPackageMapAutoExtraction = startMode.enableModPackageMapAutoExtraction,
                    enableModPackageMapRuntimeGc = startMode.enableModPackageMapRuntimeGc,
                    modPackageMapRuntimeGcGraceHours = startMode.modPackageMapRuntimeGcGraceHours,
                    modPackageMapRuntimeGcMaxDeletePerRun = startMode.modPackageMapRuntimeGcMaxDeletePerRun
                ),
                ranked = RankedSection(
                    coreNodeEnabled = startMode.rankedCoreNodeEnabled,
                    gatewayBaseUrl = startMode.rankedGatewayBaseUrl,
                    queueCode = startMode.rankedQueueCode,
                    pollIntervalSeconds = startMode.rankedPollIntervalSeconds,
                    jumpDelaySeconds = startMode.rankedJumpDelaySeconds
                ),
                advertisingRedirect = AdvertisingRedirectSection(
                    enabled = startMode.advertisingRedirectEnabled,
                    targetHost = startMode.advertisingRedirectTargetHost,
                    targetPort = startMode.advertisingRedirectTargetPort,
                    targetRoomId = startMode.advertisingRedirectTargetRoomId,
                    redirectTiming = startMode.advertisingRedirectTiming,
                    joinProbeSafeMode = startMode.advertisingRedirectJoinProbeSafeMode,
                    delaySeconds = startMode.advertisingRedirectDelaySeconds,
                    showNotice = startMode.advertisingRedirectShowNotice,
                    noticeBeforeRedirect = startMode.advertisingRedirectNoticeBeforeRedirect,
                    noticeOnManualFallback = startMode.advertisingRedirectNoticeOnManualFallback,
                    kickAfterRedirect = startMode.advertisingRedirectKickAfterRedirect,
                    cooldownSecondsPerPlayer = startMode.advertisingRedirectCooldownSecondsPerPlayer,
                    maxRedirectAttemptsPerConnection = startMode.advertisingRedirectMaxAttemptsPerConnection,
                    allowLocalhostTarget = startMode.advertisingRedirectAllowLocalhostTarget,
                    allowPrivateIpTarget = startMode.advertisingRedirectAllowPrivateIpTarget,
                    allowedTargetHosts = startMode.advertisingRedirectAllowedTargetHosts,
                    disableWhenRankedCoreNode = startMode.advertisingRedirectDisableWhenRankedCoreNode,
                    disableWhenRankedShardNode = startMode.advertisingRedirectDisableWhenRankedShardNode,
                    adminBypass = startMode.advertisingRedirectAdminBypass,
                    logRedirect = startMode.advertisingRedirectLogRedirect
                ),
                console = core.console.normalized(),
                pluginLogging = core.pluginLogging.normalized(),
                singleRoomLowLatency = server.singleRoomLowLatency.normalized()
            )
        }
    }
}
