/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.core.thread

import net.rwhps.server.core.thread.CallGroupData.coreExpandServer
import net.rwhps.server.core.thread.CallGroupData.coreServer

/**
 * Timed task identifiers used by the scheduler.
 */
enum class CallTimeTask(
    val group: String,
    val description: String
) {
    CallTeamTask(coreServer, "Update player team list"),

    PlayerAfkTask(coreServer, "Transfer of authority"),
    GameOverTask(coreServer, "Check gameover"),
    GameOverResetRetryTask(coreExpandServer, "Retry failed gameover reset"),
    BattleRoomTransitionCommitTask(coreExpandServer, "Commit battle-room return after engine packet"),
    BattleRoomTransitionWatchdogTask(coreExpandServer, "Verify battle-room return completion"),
    BattleRoomLobbyRecoveryTask(coreExpandServer, "Rebuild lobby state after battle-room return"),
    VoteTask(coreServer, "Vote"),
    AutoStartTask(coreExpandServer, "Start automatically"),
    StartConfirmationTask(coreExpandServer, "Confirm that the game core accepted start"),
    PostGameReturnTask(coreExpandServer, "Post-game battle room return settle"),
    AutoUpdateMapsTask(coreExpandServer, "Auto update maps"),
    AutoCheckTask(coreExpandServer, "Auto check player survives"),
    LatencyHeartbeatTask(coreExpandServer, "Lightweight latency heartbeat"),

    CustomUpServerListTask("UpList", "[Plugin UpList] Update Data"),
    UpServerListTask("UpList", "Core"),
    UpServerListNewTask("UpList", "Core"),

    BlackListCheckTask("[BlackList]", "Timeout cleanup"),

    ServerUploadDataTask("[RCN]", "List upload"),
    ServerHostKeepAlive("[RCN]", "Keep alive"),
    ServerUploadData_CheckTimeTask("[RCN]", "Upload timeout"),
    RelayRoom_CheckTimeTask("[RCN]", "Relay room timeout cleanup"),
    RelayCustomRoomHotReload("[RCN]", "Custom room hot reload"),
    RelayEntryWelcomeHotReload("[RCN]", "Entry welcome hot reload"),
    RelayLicenseRefresh("[RCN]", "License lease refresh"),
    RelayNodeStatusRefresh("[RCN]", "Node status sync"),
    ServerStatusUpdate("[RCN]", "Server status update"),

    ServerUpStatistics("[RW-HPS]", "Statistics update")
}
