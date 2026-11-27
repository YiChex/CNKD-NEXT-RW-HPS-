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
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.math.RandomUtils

/**
 * Server-Protocol configuration file
 *
 * Save data for serialization and deserialization
 * @author Dr (dr@der.kim)
 */
data class BeanServerConfig(
    /** Server ID used to distinguish later mods/maps/save files. */
    val serverID: String = RandomUtils.getRandomIetterString(5),

    val enterAd: String = "欢迎加入本服务器",
    val startAd: String = "服务器已就绪！",
    val maxPlayerJoinAd: String = "房间已达到管理员设置的人数上限 请稍后再试 @CNKD",
    val startPlayerJoinAd: String = "对局正在进行中 请稍后再试 @CNKD",

    /** Password */
    val passwd: String = "",

    /** Maximum number of players supported by headless mode. */
    val maxPlayer: Int = 100,
    /** only Admin (Auto) */
    val oneAdmin: Boolean = true,

    /** Minimum start player size (-1 disabled) */
    val startMinPlayerSize: Int = -1,
    /** Minimum autostart player size (-1 disabled) */
    val autoStartMinPlayerSize: Int = -1,
    /** Auto-start countdown in seconds (0 starts immediately). */
    val autoStartCountdownSeconds: Int = 5,
    /** Manual Start button/command delay; 0 starts immediately. Independent of automatic countdown. */
    val manualStartCountdownSeconds: Int = 5,
    /** Maximum game duration in seconds (-1 disabled) */
    val maxGameIngTime: Int = 7200,
    /** Maximum only-AI game duration in seconds (-1 disabled) */
    val maxOnlyAIGameIngTime: Int = 3600,

    val enableAI: Boolean = false,

    /** Max chat length */
    val maxMessageLen: Int = 100,
    /** Max unit count */
    val maxUnit: Int = 1000,
    /** Default income */
    val defIncome: Float = 1f,
    /** Exact default starting credits applied at game start. */
    val defaultStartingCreditsAmount: Int = 4000,
    /** New-lobby no-nukes default. A current host selection has higher pre-start priority. */
    val disableNukes: Boolean = false,
    /** Default fog mode: off / basic / lineOfSight. */
    val defaultFogMode: String = "lineOfSight",

    val isAfk: Boolean = true,
    val muteAll: Boolean = false,

    /** Turn stone into gold */
    val turnStoneIntoGold: Boolean = false,

    /** Print mod load error messages */
    val modsLoadErrorPrint: Boolean = false,

    /** Save replay files */
    val saveRePlayFile: Boolean = true,

    /** Single-room latency optimization profile. Enabled by default for single-room Start deployments. */
    val singleRoomLowLatency: BeanSingleRoomLowLatencyConfig = BeanSingleRoomLowLatencyConfig(),
): AbstractBeanConfig(
    this::class.java, "rwhps.config.server"
) {
    override fun save() {
        val core = runCatching { Data.config }.getOrDefault(BeanCoreConfig())
        val startMode = runCatching { Data.configStartMode }.getOrDefault(BeanStartModeConfig())
        ServerConfigRepository.save(core, this, startMode)
    }

    private fun checkValue() {
        var changed = false

        if (maxPlayer < startMinPlayerSize) {
            Log.warn("MaxPlayer < StartMinPlayerSize , Reset !")
            coverField("startMinPlayerSize", 0)
            changed = true
        }

        if (autoStartMinPlayerSize != -1 && maxPlayer < autoStartMinPlayerSize) {
            Log.warn("MaxPlayer < AutoStartMinPlayerSize , Reset !")
            coverField("autoStartMinPlayerSize", maxPlayer)
            changed = true
        }

        if (startMinPlayerSize != -1 && autoStartMinPlayerSize != -1 && autoStartMinPlayerSize < startMinPlayerSize) {
            Log.warn("AutoStartMinPlayerSize < StartMinPlayerSize , Reset !")
            coverField("autoStartMinPlayerSize", startMinPlayerSize)
            changed = true
        }

        if (maxPlayer > 100) {
            Log.warn("MaxPlayer > GameMaxPlayerSize , Reset !")
            coverField("maxPlayer", 100)
            changed = true
        }

        if (manualStartCountdownSeconds !in 0..300) {
            coverField("manualStartCountdownSeconds", manualStartCountdownSeconds.coerceIn(0, 300))
            changed = true
        }
        if (autoStartCountdownSeconds < 0) {
            Log.warn("AutoStartCountdownSeconds < 0 , Reset !")
            coverField("autoStartCountdownSeconds", 0)
            changed = true
        }

        if (defaultStartingCreditsAmount < 0) {
            Log.warn("DefaultStartingCreditsAmount < 0 , Reset !")
            coverField("defaultStartingCreditsAmount", 0)
            changed = true
        }

        val normalizedFogMode = normalizeFogMode(defaultFogMode)
        if (normalizedFogMode != defaultFogMode) {
            Log.warn("DefaultFogMode invalid , Reset !")
            coverField("defaultFogMode", normalizedFogMode)
            changed = true
        }

        if (changed) {
            save()
        }
    }

    private fun normalizeFogMode(value: String): String {
        return when (value.trim().lowercase()) {
            "off", "0", "false" -> "off"
            "basic", "1" -> "basic"
            "lineofsight", "line_of_sight", "line-of-sight", "lineofsightfog", "2", "true" -> "lineOfSight"
            else -> "off"
        }
    }

    companion object {
        val fileUtils = ServerConfigRepository.fileUtils

        @JvmStatic
        fun stringToClass(): BeanServerConfig {
            val config: BeanServerConfig = ServerConfigRepository.load().toServerConfig()
            config.bindFile(fileUtils)
            config.readProperty()
            config.checkValue()
            return config
        }
    }
}
