/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.command

import net.rwhps.server.data.bean.BeanRelayApiConfig
import net.rwhps.server.data.bean.BeanRelayLicenseConfig
import net.rwhps.server.data.bean.BeanServerConfigFile
import net.rwhps.server.data.bean.ServerConfigRepository
import net.rwhps.server.net.api.RelayLicenseSupport
import net.rwhps.server.util.inline.toGson

/**
 * Read-only configuration inspection for operators.
 *
 * This deliberately avoids repository load/repair paths so running the command
 * during a busy lobby cannot rewrite config files behind an admin's back.
 */
object ConfigCheckSupport {
    fun buildReport(): List<String> {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val tips = mutableListOf<String>()

        val serverConfig = readConfigFile(
            label = "ServerConfig.json",
            readRaw = { ServerConfigRepository.fileUtils.readFileStringData() },
            parse = { BeanServerConfigFile::class.java.toGson(it) },
            errors = errors
        )
        val relayApiConfig = readOptionalConfigFile(
            label = "RelayApiConfig.json",
            readRaw = { BeanRelayApiConfig.fileUtils.readFileStringData() },
            parse = { BeanRelayApiConfig::class.java.toGson(it) },
            defaultValue = BeanRelayApiConfig(),
            warnings = warnings
        )
        val relayLicenseConfig = readOptionalConfigFile(
            label = "RelayLicenseConfig.json",
            readRaw = { BeanRelayLicenseConfig.fileUtils.readFileStringData() },
            parse = { BeanRelayLicenseConfig::class.java.toGson(it) },
            defaultValue = BeanRelayLicenseConfig(),
            warnings = warnings
        )

        if (serverConfig != null) {
            appendServerConfigChecks(serverConfig, relayLicenseConfig, errors, warnings, tips)
        }
        appendRelayApiChecks(relayApiConfig, errors, warnings, tips)

        val lines = mutableListOf<String>()
        lines += "========== CNKD CONFIG CHECK =========="
        lines += "serverConfig=${if (serverConfig == null) "parse_failed" else "ok"}"
        lines += "relayApiConfig=ok"
        lines += "relayLicenseConfig=ok"
        lines += "errors=${errors.size}, warnings=${warnings.size}, tips=${tips.size}"

        if (errors.isNotEmpty()) {
            lines += "---------- errors ----------"
            errors.forEachIndexed { index, item -> lines += "${index + 1}. $item" }
        }
        if (warnings.isNotEmpty()) {
            lines += "---------- warnings ----------"
            warnings.forEachIndexed { index, item -> lines += "${index + 1}. $item" }
        }
        if (tips.isNotEmpty()) {
            lines += "---------- tips ----------"
            tips.distinct().forEachIndexed { index, item -> lines += "${index + 1}. $item" }
        }
        if (errors.isEmpty() && warnings.isEmpty()) {
            lines += "result=PASS"
        } else {
            lines += "result=${if (errors.isEmpty()) "WARN" else "ERROR"}"
        }
        lines += "commands.next=config check | health | api status | uplist status | advertising status"
        return lines
    }

    internal fun buildReportFor(
        serverConfig: BeanServerConfigFile,
        relayApiConfig: BeanRelayApiConfig = BeanRelayApiConfig(),
        relayLicenseConfig: BeanRelayLicenseConfig = BeanRelayLicenseConfig()
    ): List<String> {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val tips = mutableListOf<String>()
        appendServerConfigChecks(serverConfig, relayLicenseConfig, errors, warnings, tips)
        appendRelayApiChecks(relayApiConfig, errors, warnings, tips)
        return listOf("errors=${errors.size}, warnings=${warnings.size}, tips=${tips.size}") + errors + warnings + tips
    }

    private fun appendServerConfigChecks(
        config: BeanServerConfigFile,
        licenseConfig: BeanRelayLicenseConfig,
        errors: MutableList<String>,
        warnings: MutableList<String>,
        tips: MutableList<String>
    ) {
        val common = config.common
        val network = config.network
        val gameplay = config.gameplay
        val lobby = config.lobby
        val map = config.map
        val advertising = config.advertisingRedirect

        if (common.port !in 1..65535) {
            errors += "common.port 必须在 1-65535 之间，当前=${common.port}"
        }
        if (common.serverName.isBlank()) {
            errors += "common.serverName 为空，公开列表和玩家大厅会缺少可识别名称"
        }
        if (gameplay.maxPlayer !in 1..100) {
            warnings += "gameplay.maxPlayer 建议保持在 1-100，当前=${gameplay.maxPlayer}；过高会增加同步、掉线重连和内存压力"
        }
        if (gameplay.startMinPlayerSize > 0 && gameplay.startMinPlayerSize > gameplay.maxPlayer) {
            errors += "gameplay.startMinPlayerSize=${gameplay.startMinPlayerSize} 大于 maxPlayer=${gameplay.maxPlayer}，可能导致永远无法正常开局"
        }
        if (gameplay.autoStartMinPlayerSize > 0 && gameplay.autoStartMinPlayerSize > gameplay.maxPlayer) {
            errors += "gameplay.autoStartMinPlayerSize=${gameplay.autoStartMinPlayerSize} 大于 maxPlayer=${gameplay.maxPlayer}，自动开局不会触发"
        }
        if (gameplay.manualStartCountdownSeconds !in 0..300) {
            errors += "gameplay.manualStartCountdownSeconds 必须在 0-300，0 表示手动立即开始"
        }
        if (gameplay.autoStartCountdownSeconds in 1..2) {
            warnings += "gameplay.autoStartCountdownSeconds=${gameplay.autoStartCountdownSeconds} 太短，玩家刚进房可能还没完成地图/模组同步就开局"
        }
        if (gameplay.maxUnit > 1000) {
            warnings += "gameplay.maxUnit=${gameplay.maxUnit} 偏高，大型模组服可能更容易出现同步卡顿和内存峰值"
        }
        if (common.autoUpList && RelayLicenseSupport.localNoLicenseModeDisablesUpList()) {
            warnings += "当前构建为 ${RelayLicenseSupport.localNoLicenseModeLabel()}，common.autoUpList=true 也不会开放到公共列表"
        }
        if (common.autoUpList && licenseConfig.licenseKey.isBlank() && !RelayLicenseSupport.localNoLicenseModeEnabled()) {
            warnings += "common.autoUpList=true，但 RelayLicenseConfig.json 的 licenseKey 为空，公开列表授权刷新可能失败"
        }
        if (common.autoUpList && licenseConfig.licenseKey.isBlank() && RelayLicenseSupport.localNoLicenseModeAllowsUpList()) {
            tips += "当前构建为 ${RelayLicenseSupport.localNoLicenseModeLabel()}，UPLIST 不需要填写授权码"
        }
        if (common.listNamePinnedCharacterCount !in 0..64) {
            warnings += "common.listNamePinnedCharacterCount 建议保持 0-64，当前=${common.listNamePinnedCharacterCount}"
        }
        if (common.listPinnedCharacterCount !in 0..64) {
            warnings += "common.listPinnedCharacterCount 建议保持 0-64，当前=${common.listPinnedCharacterCount}"
        }
        val publicListEntries = common.publicListEntries.orEmpty()
        if (publicListEntries.size > 8) {
            warnings += "common.publicListEntries 当前配置 ${publicListEntries.size} 条，实际上传会限制为前 8 条有效入口，建议控制在 2-5 条"
        }
        val publicListEntryIds = mutableSetOf<String>()
        publicListEntries.forEachIndexed { index, entry ->
            val id = entry.id.trim().lowercase()
            if (!Regex("^[a-z0-9][a-z0-9_-]{0,39}$").matches(id)) {
                warnings += "common.publicListEntries[$index].id=${entry.id} 不合法，该入口会被忽略"
            } else if (!publicListEntryIds.add(id)) {
                warnings += "common.publicListEntries[$index].id=$id 与前面的入口重复，该入口会被忽略"
            }
            if (entry.serverName.trim().length > 80) {
                warnings += "common.publicListEntries[$index].serverName 超过 80 字符，该入口会被忽略"
            }
            if (entry.listNamePinnedCharacterCount !in 0..64 || entry.listPinnedCharacterCount !in 0..64) {
                warnings += "common.publicListEntries[$index] 的置顶字符建议保持 0-64"
            }
            if (entry.maxPlayerCount != null && entry.maxPlayerCount !in 1..200) {
                warnings += "common.publicListEntries[$index].maxPlayerCount 建议保持 1-200，非法值会按核心服人数上限处理"
            }
        }
        if (common.listExperimentEnabled && common.listPromotionModeEnabled != true) {
            warnings += "已启用 listExperimentEnabled，但未启用 listPromotionModeEnabled；建议优先使用推广模式统一管理列表展示字段"
        }
        if (common.listPromotionModeEnabled == true && common.listPromotionGameStatus?.isBlank() == true) {
            warnings += "推广模式已启用，但 listPromotionGameStatus 为空；建议使用 chat 或 battleroom"
        }
        if (common.listPromotionSortBoostEnabled == true && common.listPromotionSortBoostPrefix.isNullOrBlank()) {
            warnings += "推广排序前缀已启用，但 listPromotionSortBoostPrefix 为空，置顶/占位效果不会生效"
        }
        if (advertising.enabled) {
            val targetHost = advertising.targetHost.trim()
            val targetPort = advertising.targetPort
            if (targetHost.isBlank()) {
                errors += "advertisingRedirect.enabled=true，但 targetHost 为空，广告房无法跳转"
            }
            if (targetPort !in 1..65535) {
                errors += "advertisingRedirect.enabled=true，但 targetPort=$targetPort 不在 1-65535 范围内"
            }
            if (targetHost.isNotBlank() && !Regex("^[A-Za-z0-9.-]+$").matches(targetHost)) {
                errors += "advertisingRedirect.targetHost=$targetHost 包含不支持的字符；请使用域名或 IPv4 地址"
            }
            if (!advertising.allowLocalhostTarget && (targetHost.equals("localhost", ignoreCase = true) || targetHost.startsWith("127.") || targetHost == "0.0.0.0")) {
                errors += "advertisingRedirect 目标为本机地址，但 allowLocalhostTarget=false"
            }
            if (targetHost.equals("localhost", ignoreCase = true) && targetPort == common.port) {
                errors += "advertisingRedirect 目标指向当前广告房自身，可能形成跳转循环"
            }
            tips += "广告房已启用，建议执行 advertising status 和 advertising check，确认跳转统计与目标端口可达"
        } else if (common.autoUpList && RelayLicenseSupport.localNoLicenseModeAllowsUpList()) {
            tips += "当前允许 UPLIST 且广告房未启用；如这是广告房入口，请确认是否需要设置 advertisingRedirect.enabled=true"
        }
        if (network.webPort !in 0..65535) {
            errors += "network.webPort 必须为 0 或 1-65535，当前=${network.webPort}"
        }
        if (network.webPort > 0 && isWeakSecret(network.webToken)) {
            warnings += "network.webPort 已开启，但 webToken 仍为空/默认/过短；公开机器建议立即更换"
        }
        if (network.rconPort > 0 && isWeakSecret(network.rconPasswd)) {
            warnings += "network.rconPort 已开启，但 rconPasswd 仍为空/默认/过短；存在远程管理风险"
        }
        if ((network.sslEnable || network.sslMixEnable) && isWeakSecret(network.sslPasswd)) {
            warnings += "SSL 已启用，但 sslPasswd 仍为空/默认/过短；建议更换为独立密码"
        }
        if (map.enableMapLock && map.lockMap.isBlank()) {
            errors += "map.enableMapLock=true，但 map.lockMap 为空，房主首屏地图可能无法被正确锁定"
        }
        if (lobby.enableSurvivalMapMode && gameplay.maxPlayer < 2) {
            warnings += "已启用生存图模式，但 maxPlayer=${gameplay.maxPlayer}，公开服体验可能异常"
        }
        if (lobby.enableLobbySyncOptimization == false) {
            warnings += "lobby.enableLobbySyncOptimization=false；模组房或高频变更大厅更容易出现右侧同步刷屏和客户端卡顿"
        }
        if (lobby.enableLobbyGameSyncPacketFilter == false) {
            tips += "lobby.enableLobbyGameSyncPacketFilter=false；大型模组在战役室内可能更容易重复触发同步提示"
        }
        val refreshHeartbeat = lobby.lobbyRefreshHeartbeatMillis ?: 30_000
        val packetHeartbeat = lobby.lobbyPacketHeartbeatMillis ?: 30_000
        val latencyHeartbeat = lobby.latencyHeartbeatMillis ?: 1_000
        val gameSyncHeartbeat = lobby.lobbyGameSyncPacketHeartbeatMillis ?: 3_000
        val gameSyncJoinGrace = lobby.lobbyGameSyncPacketJoinGraceMillis ?: 180_000
        val reconnectGrace = lobby.reconnectGraceMillis ?: 5_000
        val gameOverReturnCountdown = lobby.gameOverReturnCountdownSeconds ?: 30
        val survivalGameOverGrace = lobby.survivalGameOverGraceSeconds ?: 180
        val survivalEnemyClearConfirm = lobby.survivalEnemyClearConfirmSeconds ?: 45
        if (refreshHeartbeat !in 30_000..120_000 || packetHeartbeat !in 30_000..120_000) {
            warnings += "大厅重同步心跳建议保持 30000-120000ms：lobbyRefreshHeartbeatMillis=$refreshHeartbeat, lobbyPacketHeartbeatMillis=$packetHeartbeat；延迟显示请使用独立的 latencyHeartbeatMillis"
        }
        if (gameSyncHeartbeat !in 500..120_000) {
            warnings += "战役室同步/校验包保底心跳建议保持 500-120000ms：lobbyGameSyncPacketHeartbeatMillis=$gameSyncHeartbeat"
        }
        if (latencyHeartbeat !in 1_000..60_000) {
            warnings += "lobby.latencyHeartbeatMillis 建议保持 1000-60000ms，当前 $latencyHeartbeat"
        }
        if (latencyHeartbeat == 10_000) {
            tips += "检测到旧版默认 latencyHeartbeatMillis=10000；新版运行时会自动按 1000ms 处理，用于每秒刷新延迟显示"
        }
        if (gameSyncJoinGrace !in 0..600_000) {
            warnings += "lobby.lobbyGameSyncPacketJoinGraceMillis 建议保持 0-600000ms，当前=$gameSyncJoinGrace"
        } else if (gameSyncJoinGrace < 60_000) {
            tips += "大型模组服建议 lobbyGameSyncPacketJoinGraceMillis 不低于 60000ms，避免直连时校验包被过早过滤"
        }
        if (reconnectGrace !in 0..60_000) {
            warnings += "lobby.reconnectGraceMillis 建议保持 0-60000ms，当前=$reconnectGrace"
        } else if (reconnectGrace < 3_000) {
            tips += "reconnectGraceMillis 当前低于 3000ms，移动网络玩家短线重连时仍可能触发完整离开/进入广播"
        }
        if (lobby.enableGameOverBattleRoomReturn != true) {
            tips += "lobby.enableGameOverBattleRoomReturn 未显式开启；赛后将提示强制重置，不会自动返回战役室，重置后玩家需要重新进入"
        }
        if (gameOverReturnCountdown !in 5..300) {
            warnings += "lobby.gameOverReturnCountdownSeconds 建议保持 5-300，当前=$gameOverReturnCountdown"
        } else if (gameOverReturnCountdown < 15) {
            tips += "gameOverReturnCountdownSeconds 低于 15 秒时，玩家可能来不及查看结算或确认赛后处理方式"
        }
        if (lobby.enableSurvivalMapMode && survivalGameOverGrace !in 0..900) {
            warnings += "lobby.survivalGameOverGraceSeconds 建议保持 0-900，当前=$survivalGameOverGrace"
        }
        if (lobby.enableSurvivalMapMode && survivalEnemyClearConfirm !in 0..300) {
            warnings += "lobby.survivalEnemyClearConfirmSeconds 建议保持 0-300，当前=$survivalEnemyClearConfirm"
        }
        if (lobby.enableJoinReadinessTips == false) {
            tips += "lobby.enableJoinReadinessTips=false；玩家入房时不会收到地图、模组和自动开局提示"
        }
        val modWarningThreshold = lobby.joinTipModWarningThreshold ?: 1
        if (modWarningThreshold !in 0..1000) {
            warnings += "lobby.joinTipModWarningThreshold 建议保持 0-1000，当前=$modWarningThreshold"
        }
        val modArchiveLimit = map.modPackageMapScanMaxArchiveSizeMb ?: 512
        if (map.enableModPackageMapDiscovery == false) {
            tips += "map.enableModPackageMapDiscovery=false；.rwmod 内置地图不会自动出现在 maps 列表中"
        }
        if (modArchiveLimit !in 0..4096) {
            warnings += "map.modPackageMapScanMaxArchiveSizeMb 建议保持 0-4096，当前=$modArchiveLimit"
        }
        if (map.enableModPackageMapDiscovery != false && map.enableModPackageMapSupportExtraction == false) {
            tips += "map.enableModPackageMapSupportExtraction=false；模组包地图可被发现，但选中时不会自动释放 _map.png/.tsx 等支撑文件"
        }
        val runtimeGcGraceHours = map.modPackageMapRuntimeGcGraceHours ?: 24
        val runtimeGcMaxDeletes = map.modPackageMapRuntimeGcMaxDeletePerRun ?: 20
        if (runtimeGcGraceHours !in 0..8760) {
            warnings += "map.modPackageMapRuntimeGcGraceHours 建议保持 0-8760，当前=$runtimeGcGraceHours"
        }
        if (runtimeGcMaxDeletes !in 1..1000) {
            warnings += "map.modPackageMapRuntimeGcMaxDeletePerRun 建议保持 1-1000，当前=$runtimeGcMaxDeletes"
        }
        if (map.enableModPackageMapRuntimeGc == false) {
            tips += "map.enableModPackageMapRuntimeGc=false；删除或替换模组后产生的运行地图缓存不会自动回收"
        }
        if (gameplay.saveRePlayFile) {
            tips += "长期公开服开启 saveRePlayFile=true 时，请定期执行 maintenance plan / clean-replays，避免回放目录持续膨胀"
        }
        if (common.autoUpList) {
            tips += "公开列表服上线后建议依次执行 uplist status、health、config check、advertising status，确认上传、内存、配置和广告跳转均正常"
        }
    }

    private fun appendRelayApiChecks(
        config: BeanRelayApiConfig,
        errors: MutableList<String>,
        warnings: MutableList<String>,
        tips: MutableList<String>
    ) {
        if (config.enableAdminApi && config.adminApiToken.isBlank()) {
            errors += "RelayApiConfig.enableAdminApi=true，但 adminApiToken 为空，管理 API 无法安全使用"
        }
        if (config.enableAdminApi && !config.adminApiRequireWhitelist) {
            warnings += "管理 API 未启用 IP 白名单；建议仅允许可信后台或反向代理访问"
        }
        if (config.enableRoomsApi && !config.roomsApiRequireWhitelist && config.corsAllowAllOrigins) {
            warnings += "rooms API 允许任意来源抓取房间列表；如果面板流量不可控，建议开启白名单或降低轮询频率"
        }
        if (config.corsAllowAllOrigins && config.corsAllowCredentials) {
            warnings += "CORS 同时允许任意来源和凭据，建议改为固定 corsAllowOrigins"
        }
        if (config.statusApiCacheMillis < 0 || config.roomsApiCacheMillis < 0) {
            errors += "API 缓存时间不能为负数：statusApiCacheMillis=${config.statusApiCacheMillis}, roomsApiCacheMillis=${config.roomsApiCacheMillis}"
        }
        if (config.statusApiCacheMillis == 0 || config.roomsApiCacheMillis == 0) {
            warnings += "API 缓存被关闭；外部面板高频轮询时会直接增加主链路压力"
        }
        if (!config.apiRateLimitEnabled) {
            warnings += "API 限流已关闭；如果公网开放面板接口，建议保持开启"
        }
        if (config.statusApiRateLimitPerMinute <= 0 || config.roomsApiRateLimitPerMinute <= 0 || config.adminApiRateLimitPerMinute <= 0) {
            errors += "API 限流阈值必须大于 0"
        }
        if (config.roomsApiLimit > 5000) {
            warnings += "roomsApiLimit=${config.roomsApiLimit} 偏高，外部面板拉全量房间时可能增加内存和 CPU 压力"
        }
        tips += "外部面板建议优先轮询 /relay/status?limit=20，只有人工刷新或详情页再请求 /relay/rooms"
    }

    private fun isWeakSecret(value: String): Boolean {
        val normalized = value.trim()
        return normalized.isBlank() ||
            normalized.equals("RW-HPS", ignoreCase = true) ||
            normalized.equals("PLEASE_CHANGE_ME", ignoreCase = true) ||
            normalized.length < 8
    }

    private fun <T> readConfigFile(
        label: String,
        readRaw: () -> String,
        parse: (String) -> T,
        errors: MutableList<String>
    ): T? {
        val raw = runCatching { readRaw() }.getOrElse {
            errors += "$label 读取失败：${it.message ?: it::class.java.simpleName}"
            return null
        }
        if (raw.isBlank()) {
            errors += "$label 为空或不存在"
            return null
        }
        return runCatching { parse(raw) }.getOrElse {
            errors += "$label 解析失败：${it.message ?: it::class.java.simpleName}"
            null
        }
    }

    private fun <T> readOptionalConfigFile(
        label: String,
        readRaw: () -> String,
        parse: (String) -> T,
        defaultValue: T,
        warnings: MutableList<String>
    ): T {
        val raw = runCatching { readRaw() }.getOrDefault("")
        if (raw.isBlank()) {
            return defaultValue
        }
        return runCatching { parse(raw) }.getOrElse {
            warnings += "$label 解析失败，将按默认值检查：${it.message ?: it::class.java.simpleName}"
            defaultValue
        }
    }
}
