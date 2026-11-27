/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.data.bean

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.inline.toGson

data class BeanStartModeConfig(
    /** Lock map selection only; player position/team locks are configured independently below. */
    val enableMapLock: Boolean = false,
    /** Map name without suffix, for example: 两岸之争 */
    val lockMap: String = "",
    /** Enable survival map mode with a reserved system AI on slot 2. */
    val enableSurvivalMapMode: Boolean = false,
    /** Ignore generic one-team game-over verdicts during survival map startup/countdown. */
    val survivalGameOverGraceSeconds: Int = 180,
    /** Confirm enemy-side units have stayed clear this long before accepting a human-side survival verdict. */
    val survivalEnemyClearConfirmSeconds: Int = 45,
    /** Default false: players may change their own team, including S. True also blocks entering S. */
    val disableTeamChange: Boolean = false,
    /** Default false: players may move themselves. True also blocks entering S, which allocates a spectator slot. */
    val disablePositionChange: Boolean = false,
    /** Allow the room owner or a permanent administrator to override team locks. */
    val adminCanOverrideTeamRules: Boolean = true,
    /** Allow the room owner or a permanent administrator to override position locks. */
    val adminCanOverridePositionRules: Boolean = true,
    /** Force each player to use a unique team number. */
    val enableFreeForAllMode: Boolean = false,
    /** When FFA is disabled, auto-assign joining players to A/B teams. */
    val enableAlternateTeamsOnJoin: Boolean = false,
    /** Reduce redundant battle-room sync packets before the game starts. */
    val enableLobbySyncOptimization: Boolean = true,
    /** Force a lobby refresh at least this often, even if the lobby signature is unchanged. */
    val lobbyRefreshHeartbeatMillis: Int = 30_000,
    /** Forward an unchanged lobby packet at least this often, so clients do not look stale. */
    val lobbyPacketHeartbeatMillis: Int = 30_000,
    /** Send a lightweight heartbeat at this interval so clients can refresh latency without lobby sync churn. */
    val latencyHeartbeatMillis: Int = 1_000,
    /** Drop duplicate pre-game sync/check packets that some large mods repeatedly emit in the battle room. */
    val enableLobbyGameSyncPacketFilter: Boolean = true,
    /** Forward an unchanged pre-game sync/check packet at least this often. */
    val lobbyGameSyncPacketHeartbeatMillis: Int = 3_000,
    /** Do not filter pre-game sync/check packets during the first join window. Large mods need this handshake time. */
    val lobbyGameSyncPacketJoinGraceMillis: Int = 180_000,
    /** Delay final leave handling so short disconnects can reconnect without noisy full-room updates. */
    val reconnectGraceMillis: Int = 5_000,
    /** Explicitly enable in-game countdown return to battle room instead of resetting the server. */
    val enableGameOverBattleRoomReturn: Boolean = false,
    /** Wait before the configured post-game battle-room return or forced server reset. */
    val gameOverReturnCountdownSeconds: Int = 30,
    /** Remove players that are still disconnected when the post-game return starts. */
    val pruneDisconnectedPlayersOnGameOver: Boolean = true,
    /** Send a concise readiness summary when a player joins the lobby. */
    val enableJoinReadinessTips: Boolean = false,
    /** Warn joining players when loaded mod count reaches this value. */
    val joinTipModWarningThreshold: Int = 1,
    /** Discover .tmx maps bundled inside .rwmod/.zip packages under data/mods. */
    val enableModPackageMapDiscovery: Boolean = true,
    /** Skip very large mod archives during map discovery to avoid startup spikes. 0 disables the size limit. */
    val modPackageMapScanMaxArchiveSizeMb: Int = 512,
    /** Extract map-side support files such as .tsx and _map.png when a mod-pack map is selected. */
    val enableModPackageMapSupportExtraction: Boolean = true,
    /** Prepare all discovered mod-pack maps at startup so visual map selectors can switch to them safely. */
    val enableModPackageMapAutoExtraction: Boolean = true,
    /** Reclaim stale server-generated mod-map runtime directories. */
    val enableModPackageMapRuntimeGc: Boolean = true,
    /** Keep an unused runtime directory for this many hours before reclamation. */
    val modPackageMapRuntimeGcGraceHours: Int = 24,
    /** Maximum number of runtime directories removed by one cleanup pass. */
    val modPackageMapRuntimeGcMaxDeletePerRun: Int = 20,
    /** Make this server act as a ranked entry node: players auto queue and jump to battle shards. */
    val rankedCoreNodeEnabled: Boolean = false,
    /** Rank gateway base URL used by the ranked entry node. */
    val rankedGatewayBaseUrl: String = "http://127.0.0.1:3010",
    /** Queue code used when players join the ranked entry node. */
    val rankedQueueCode: String = "ranked_1v1",
    /** How often the ranked entry node polls player dispatch state. */
    val rankedPollIntervalSeconds: Int = 2,
    /** Small delay before sending the client jump packet after a shard is reserved. */
    val rankedJumpDelaySeconds: Int = 1,
    /** Enable advertising room mode. Players enter the room first, then receive a redirect hint. */
    val advertisingRedirectEnabled: Boolean = false,
    /** Target host used by advertising room redirects. */
    val advertisingRedirectTargetHost: String = "101.43.41.22",
    /** Target port used by advertising room redirects. */
    val advertisingRedirectTargetPort: Int = 5123,
    /** Optional room id suffix appended to the redirect endpoint when supported by the client. */
    val advertisingRedirectTargetRoomId: String = "",
    /** Redirect timing. P0 only supports afterJoin to keep public-list probes healthy. */
    val advertisingRedirectTiming: String = "afterJoin",
    /** Prevent redirects before the player has really joined the room. */
    val advertisingRedirectJoinProbeSafeMode: Boolean = true,
    /** Delay between join notice and redirect packet. */
    val advertisingRedirectDelaySeconds: Int = 2,
    /** Send a private notice before redirecting the player. */
    val advertisingRedirectShowNotice: Boolean = true,
    /** Private notice sent before the redirect packet. */
    val advertisingRedirectNoticeBeforeRedirect: String = "欢迎来到 CNKD 导航服 正在为你跳转到主服",
    /** Manual fallback notice shown before redirecting. */
    val advertisingRedirectNoticeOnManualFallback: String = "如未自动跳转 请手动加入 {host}:{port}",
    /** Disconnect the player from the advertising room after sending the redirect packet. */
    val advertisingRedirectKickAfterRedirect: Boolean = true,
    /** Per-player cooldown to avoid redirect storms. */
    val advertisingRedirectCooldownSecondsPerPlayer: Int = 10,
    /** Maximum redirect attempts for one connection. */
    val advertisingRedirectMaxAttemptsPerConnection: Int = 1,
    /** Allow localhost targets such as 127.0.0.1 or localhost. */
    val advertisingRedirectAllowLocalhostTarget: Boolean = false,
    /** Allow private IPv4 targets such as 10.x, 172.16-31.x, or 192.168.x. */
    val advertisingRedirectAllowPrivateIpTarget: Boolean = true,
    /** Optional host whitelist. Empty means no whitelist enforcement. */
    val advertisingRedirectAllowedTargetHosts: List<String> = emptyList(),
    /** Disable advertising redirects when this server is a ranked core node. */
    val advertisingRedirectDisableWhenRankedCoreNode: Boolean = true,
    /** Disable advertising redirects when this server is a ranked shard node. */
    val advertisingRedirectDisableWhenRankedShardNode: Boolean = true,
    /** Let real administrators stay in the advertising room for inspection. */
    val advertisingRedirectAdminBypass: Boolean = true,
    /** Log advertising redirect decisions to the console. */
    val advertisingRedirectLogRedirect: Boolean = true,
    /** Allow individual surrender. false = require full team surrender vote. */
    val enableIndividualSurrender: Boolean = false,
    /** Disconnect a human player after the original game core confirms that all of their units are destroyed. */
    val enableAutoKickPlayerWhenAllUnitsDestroyed: Boolean = false,
    /** Native kick-dialog text shown to a player removed after all of their units are destroyed. */
    val autoKickPlayerWhenAllUnitsDestroyedMessage: String = BeanServerConfigFile.DEFAULT_AUTO_KICK_DESTROYED_PLAYER_MESSAGE,
): AbstractBeanConfig(
    this::class.java, "rwhps.config.startMode"
) {
    override fun save() {
        val core = runCatching { Data.config }.getOrDefault(BeanCoreConfig())
        val server = runCatching { Data.configServer }.getOrDefault(BeanServerConfig())
        ServerConfigRepository.save(core, server, this)
    }

    companion object {
        val fileUtils = ServerConfigRepository.fileUtils

        @JvmStatic
        fun stringToClass(): BeanStartModeConfig {
            val config = ServerConfigRepository.load().toStartModeConfig()
            config.bindFile(fileUtils)
            config.readProperty()
            return config
        }
    }
}
