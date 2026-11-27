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
import net.rwhps.server.util.SystemUtils
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.inline.toGson
import net.rwhps.server.util.math.RandomUtils


data class BeanPublicListEntry(
    val id: String = "main",
    val enabled: Boolean = true,
    val serverName: String = "",
    val subtitle: String = "",
    val mapName: String = "",
    val statusSuffixEnabled: Boolean = true,
    val listNamePinnedCharacterCount: Int = 0,
    val listPinnedCharacterCount: Int = 0,
    val maxPlayerCount: Int? = null
)

data class BeanConsoleConfig(
    val verbosity: String = "normal",
    val showPlayerJoinLeave: Boolean = true,
    val showPlayerChat: Boolean = false,
    val showLowLatencySummary: Boolean = false,
    val showSurvivalRepair: Boolean = false,
    val showRankedCompatibility: Boolean = false
) {
    fun normalized(): BeanConsoleConfig {
        return copy(
            verbosity = normalizeVerbosity(verbosity)
        )
    }

    companion object {
        private val VERBOSITY_VALUES = setOf("quiet", "normal", "verbose", "debug")

        fun normalizeVerbosity(rawValue: String?): String {
            val normalized = rawValue.orEmpty().trim().lowercase()
            return if (normalized in VERBOSITY_VALUES) normalized else "normal"
        }
    }
}

/**
 * The server is primarily controlled
 *
 * Save data for serialization and deserialization
 * @author Dr (dr@der.kim)
 */
data class BeanCoreConfig(
    val noteEnglish: String = """
        Different protocols use different configuration files, please go to the corresponding file ConfigServer/ConfigRelay
    """.trimIndent(), val noteChina: String = """
        不同协议使用的配置文件不同, 请自行前往对应文件 ConfigServer/ConfigRelay
    """.trimIndent(),

    /** Default startup command */
    val defStartCommand: String = "start",

    val log: String = "WARN",

    /** Maximum size of the active core error log file before it is rotated. */
    val errorLogMaxSizeMb: Int = 8,
    /** Number of rotated core error log files retained beside Log.txt. */
    val errorLogRetainFiles: Int = 3,

    val cmdTitle: String = "",

    /** 更新是否使用测试版本 */
    val followBetaVersion: Boolean = false,

    /** Port */
    val port: Int = 5123,

    /** 服务器名称 */
    val serverName: String = "CNKD NEXT Start 服",
    /** Sender name shown for server system messages in the in-game chat bar. */
    val chatSenderName: String = "CNKD",
    /** 标题, 留空使用地图名 */
    val subtitle: String = "",
    /** Automatically after starting UPLIST */
    val autoUpList: Boolean = false,
    /** Whether to append list status suffixes such as idle/normal/busy to the server name. */
    val listStatusSuffixEnabled: Boolean = false,
    /** Number of pinned prefix characters added to the uploaded server name. */
    val listNamePinnedCharacterCount: Int = 0,
    /** Number of pinned prefix characters added to the uploaded map name. */
    val listPinnedCharacterCount: Int = 0,
    /** Optional display aliases uploaded to the public list for the same core lobby. */
    val publicListEntries: List<BeanPublicListEntry> = emptyList(),
    /** Enable experimental public-list field overrides for sorting research. */
    val listExperimentEnabled: Boolean = false,
    /** Optional override for the uploaded game_name field. Blank keeps the default value. */
    val listExperimentGameName: String = "",
    /** Optional override for the uploaded created_by field. Blank keeps the runtime value. */
    val listExperimentCreatedBy: String = "",
    /** Optional override for the uploaded game_map field. Blank keeps the runtime value. */
    val listExperimentMapName: String = "",
    /** Optional override for the uploaded game_status field. Blank keeps the runtime value. */
    val listExperimentGameStatus: String = "",
    /** Optional override for the uploaded player_count field. -1 keeps the runtime value. */
    val listExperimentPlayerCount: Int = -1,
    /** Optional override for the uploaded max_player_count field. -1 keeps the runtime value. */
    val listExperimentMaxPlayerCount: Int = -1,
    /** Enable safe promotion-list mode with stable visible status/counts. */
    val listPromotionModeEnabled: Boolean = false,
    /** Uploaded game_status used by promotion mode. */
    val listPromotionGameStatus: String = "chat",
    /** Stable visible player_count used by promotion mode. -1 falls back to legacy experiment/default promotion value. */
    val listPromotionPlayerCount: Int = -1,
    /** Stable visible max_player_count used by promotion mode. -1 falls back to legacy experiment/default promotion value. */
    val listPromotionMaxPlayerCount: Int = -1,
    /** Enable safe sort boost for promotion mode without creating fake mod mismatch rooms. */
    val listPromotionSortBoostEnabled: Boolean = false,
    /** Prefix used when sort boost needs to build a promoted created_by value. */
    val listPromotionSortBoostPrefix: String = "\uD808\uDC31",
    /** Force a blank uploaded map field in promotion sort boost mode. */
    val listPromotionBlankMapEnabled: Boolean = false,

    /** Controls operational console noise. Actual routing is implemented by StartMode observability support. */
    val console: BeanConsoleConfig = BeanConsoleConfig(),
    /** Controls V2 plugin logging independently from the global [log] threshold. */
    val pluginLogging: BeanPluginLoggingConfig = BeanPluginLoggingConfig(),

    /** ip多语言支持 */
    val ipCheckMultiLanguageSupport: Boolean = true,

    /** Single user relay disable pop-up selection */
    val singleUserRelay: Boolean = false,
    /** Default mods configuration for single user relay */
    val singleUserRelayMod: Boolean = false,

    /** 混合协议监听, 会使用 [BeanCoreConfig.port] 作为HTTP/HTTPS/R-Con/Game, 支持SSL */
    val mixProtocolEnable: Boolean = false,
    val rconMixEnable: Boolean = false,
    val webMixEnable: Boolean = false,

    val rconPort: Int = 0,
    val rconPasswd: String = RandomUtils.getRandomIetterString(10),

    /** Web的 Port, 不为 0 时在对应端口启用一个HTTP服务 */
    val webPort: Int = 0,
    /** Test : HTTP 鉴权 */
    val webToken: String = RandomUtils.getRandomIetterString(10),
    /** Web HOST 限制 */
    val webHOST: String = "",
    /** 启用SSL支持(需要使用jks) */
    val sslEnable: Boolean = false,
    /** 混合协议, 允许在启用SSL的情况下, 同端口服务HTTP */
    val sslMixEnable: Boolean = false,
    /** SSL密码 */
    val sslPasswd: String = "RW-HPS",

    var runPid: Long = 0
): AbstractBeanConfig(
        this::class.java, "rwhps.config.core"
) {
    override fun save() {
        runPid = SystemUtils.pid
        val server = runCatching { Data.configServer }.getOrDefault(BeanServerConfig())
        val startMode = runCatching { Data.configStartMode }.getOrDefault(BeanStartModeConfig())
        ServerConfigRepository.save(this, server, startMode)
    }

    companion object {
        val fileUtils = ServerConfigRepository.fileUtils

        @JvmStatic
        fun stringToClass(): BeanCoreConfig {
            val config: BeanCoreConfig = ServerConfigRepository.load().toCoreConfig()
            config.bindFile(fileUtils)
            config.readProperty()
            return config
        }
    }
}
