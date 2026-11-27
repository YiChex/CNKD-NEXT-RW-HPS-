/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.core.thread.Threads
import net.rwhps.server.game.headless.core.AbstractGameModule
import net.rwhps.server.game.headless.core.link.FullGameSyncLink
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.util.log.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object StartModeMoneyCommandSupport {
    private const val MAX_SINGLE_DELTA = 1_000_000
    private const val MAX_PLAYER_CREDITS = 10_000_000
    private const val SYNC_DEBOUNCE_MILLIS = 750L

    private data class PendingSync(val module: AbstractGameModule, val generation: Long)
    private val pendingSync = AtomicReference<PendingSync?>()
    private val syncWorkerRunning = AtomicBoolean(false)
    private val syncExecutor = ScheduledThreadPoolExecutor(1) { job ->
        Thread(job, "StartMode-Credits-Sync").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun addMoney(
        module: AbstractGameModule,
        positionText: String,
        amountText: String
    ): MoneyCommandResult {
        if (!module.room.isStartGame) {
            return MoneyCommandResult(false, "游戏尚未开始，addmoney 只能在对局中使用。")
        }

        val site = positionText.trim().toIntOrNull()?.minus(1)
            ?: return MoneyCommandResult(false, "玩家位置必须是数字。")
        val player = module.room.playerManage.getPlayer(site)
            ?: return MoneyCommandResult(false, "未找到位置 ${positionText.trim()} 对应的玩家。")

        val delta = amountText.trim().toLongOrNull()
            ?: return MoneyCommandResult(false, "金额必须是整数。")
        if (delta == 0L) {
            return MoneyCommandResult(false, "金额不能为 0。")
        }
        if (delta > MAX_SINGLE_DELTA || delta < -MAX_SINGLE_DELTA) {
            return MoneyCommandResult(
                false,
                "单次 addmoney 金额过大，当前限制为 -$MAX_SINGLE_DELTA 到 $MAX_SINGLE_DELTA。"
            )
        }

        var before = 0
        var after = 0
        val generation = module.room.lobbyLifecycleGeneration()
        var applied = false
        val dispatched = module.gameFunction.suspendMainThreadOperations {
            if (!module.room.isStartGame || module.room.lobbyLifecycleGeneration() != generation ||
                module.room.playerManage.getPlayer(site) !== player
            ) return@suspendMainThreadOperations
            before = safeCredits(player)
            after = (before.toLong() + delta).coerceIn(0, MAX_PLAYER_CREDITS.toLong()).toInt()
            player.credits = after
            applied = true
        }
        if (!dispatched || !applied) {
            return MoneyCommandResult(false, "资金未调整：游戏线程操作失败，或玩家/对局状态已变化。")
        }

        requestDebouncedSync(module, generation)
        return MoneyCommandResult(
            true,
            "已调整 ${player.name} 的资金：$before -> $after（变化 ${after - before}）。同步将自动合并发送。"
        )
    }

    private fun safeCredits(player: PlayerHess): Int {
        // A broken player accessor must fail the queued operation, never become a synthetic 0 balance.
        return player.credits.coerceIn(0, MAX_PLAYER_CREDITS)
    }

    private fun requestDebouncedSync(module: AbstractGameModule, generation: Long) {
        pendingSync.set(PendingSync(module, generation))
        startSyncWorker()
    }

    private fun startSyncWorker() {
        if (!syncWorkerRunning.compareAndSet(false, true)) return
        try {
            syncExecutor.schedule({
                try {
                    val request = pendingSync.getAndSet(null)
                    if (request != null && request.module.room.isStartGame &&
                        request.module.room.lobbyLifecycleGeneration() == request.generation) {
                        val link = request.module.gameLinkFunction as? FullGameSyncLink
                        if (link == null || !link.canSyncFullGameState()) {
                            Log.warn("[StartMode] Credits changed, but world sync is not ready; client consistency is unconfirmed")
                        } else {
                            val result = link.syncFullGameStateForGeneration(request.generation)
                            if (!result.success) Log.warn("[StartMode] Credits world sync failed; client consistency is unconfirmed",
                                result.error ?: IllegalStateException("full_game_sync_failed"))
                        }
                    }
                } catch (error: Exception) {
                    Log.warn("[StartMode] Credits sync worker failed", error)
                } finally {
                    syncWorkerRunning.set(false)
                    if (pendingSync.get() != null) startSyncWorker()
                }
            }, SYNC_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            syncWorkerRunning.set(false)
            Log.warn("[StartMode] Cannot schedule credits world sync", error)
        }
    }

    data class MoneyCommandResult(
        val success: Boolean,
        val message: String
    )
}
