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

data class BeanRoomPermissionRoleConfig(
    val allowStartGame: Boolean = true,
    val allowChangeMap: Boolean = true,
    val allowViewMaps: Boolean = true,
    val allowKickPlayers: Boolean = true,
    val allowAddAi: Boolean = true,
    val allowSetCredits: Boolean = true,
    val allowSetIncome: Boolean = true,
    val allowSetFog: Boolean = true,
    val allowSetStartingUnits: Boolean = true,
    val allowToggleNukes: Boolean = true,
    val allowToggleSharedControl: Boolean = true,
    val allowSetAiDifficulty: Boolean = true,
    val allowMovePlayers: Boolean = true,
    val allowChangeTeam: Boolean = true,
    val allowTransferHost: Boolean? = true,
    val allowToggleMixedTeam: Boolean = true,
    val allowReturnToBattleRoom: Boolean = true,
    val allowPauseGame: Boolean = true,
    val allowUnpauseGame: Boolean = true,
    val allowSetPlayerStartUnit: Boolean = true,
    val allowSpawnUnits: Boolean = true,
    val allowAddMoneyInGame: Boolean = true,
    val allowFireNukeInGame: Boolean = true,
    val allowCloneUnits: Boolean = true
)

data class BeanRoomPermissionPlayerConfig(
    val allowVoteGameOver: Boolean = true,
    val allowAfkTakeAdmin: Boolean = true,
    val allowViewStatus: Boolean = true,
    val allowViewMyId: Boolean = true,
    val allowViewMaps: Boolean = true,
    val allowStartGame: Boolean = false,
    val allowChangeMap: Boolean = false,
    val allowAddAi: Boolean = false,
    val allowSetCredits: Boolean = false,
    val allowSetIncome: Boolean = false,
    val allowSetFog: Boolean = false,
    val allowSetStartingUnits: Boolean = false,
    val allowToggleNukes: Boolean = false,
    val allowToggleSharedControl: Boolean = false,
    val allowSetAiDifficulty: Boolean = false,
    /** Delegated authority over other players. Own placement remains available unless lobby locks apply. */
    val allowMovePlayers: Boolean = true,
    /** Delegated authority over other players. Own team/S selection uses the lobby's team rules. */
    val allowChangeTeam: Boolean = true,
    val allowTransferHost: Boolean = false,
    val allowToggleMixedTeam: Boolean = false
)

data class BeanRoomPermissionModerationConfig(
    val superAdminBypass: Boolean = true,
    val allowAutoAdminAssignment: Boolean = true,
    val allowAutoAdminTransferOnLeave: Boolean = true,
    val allowManualAdminGrantFromConsole: Boolean = true,
    val allowGiveAdminFromConsole: Boolean = true
)

data class BeanRoomPermissionConfig(
    val enable: Boolean = true,
    val moderation: BeanRoomPermissionModerationConfig = BeanRoomPermissionModerationConfig(),
    val host: BeanRoomPermissionRoleConfig = BeanRoomPermissionRoleConfig(),
    val admin: BeanRoomPermissionRoleConfig = BeanRoomPermissionRoleConfig(),
    val player: BeanRoomPermissionPlayerConfig = BeanRoomPermissionPlayerConfig()
): AbstractBeanConfig(
    this::class.java, "rwhps.config.roomPermission"
) {
    companion object {
        val fileUtils = FileUtils.getFolder(Data.ServerDataPath).toFile("RoomPermissionConfig.json")

        @JvmStatic
        fun stringToClass(): BeanRoomPermissionConfig {
            val raw = fileUtils.readFileStringData()
            val config = if (raw.isBlank()) {
                BeanRoomPermissionConfig()
            } else {
                BeanRoomPermissionConfig::class.java.toGson(raw)
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
