/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.headless.core.link

/** Result of repairing native engine team slots during lobby recovery. */
internal data class LobbyTeamRepairResult(
    val success: Boolean,
    val repaired: Int = 0,
    val missing: Int = 0,
    val error: Throwable? = null
)

/** Internal engine capability used only by the Start-mode battle-room recovery path. */
internal interface BattleRoomLobbyRecoveryLink {
    fun refreshLobbyStateAfterBattleRoomReturn(): LobbyPlayerSyncResult

    /** Repairs native engine team slots inside the Headless class-loader boundary. */
    fun repairLobbyTeamsAfterBattleRoomReturn(restoredTeams: Map<String, Int>): LobbyTeamRepairResult
}
