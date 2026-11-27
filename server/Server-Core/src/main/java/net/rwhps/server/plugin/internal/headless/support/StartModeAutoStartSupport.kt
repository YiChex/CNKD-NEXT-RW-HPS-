/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.core.thread.CallTimeTask
import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.global.Data
import net.rwhps.server.data.global.NetStaticData
import net.rwhps.server.game.GameMaps
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.util.Time
import net.rwhps.server.util.log.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal object StartModeAutoStartSupport {
    private const val START_REQUEST_DEBOUNCE_MILLIS = 3000L
    private const val START_CONFIRMATION_TIMEOUT_SECONDS = 8

    @Volatile
    private var lastStartRequestAt = 0L

    private val startOperationInProgress = AtomicBoolean(false)

    /**
     * A1-01: bounds start DISPATCH to one in flight at a time.
     *
     * Separate from [startOperationInProgress] on purpose: that one is CASed inside requestStart
     * (after lobby-rule enforcement, presence snapshot, mode and map validation), so it cannot
     * stop several concurrent requests from each spawning a thread before any of them wins it.
     * This flag is CASed before the thread exists, which is what actually bounds thread creation.
     */
    private val startDispatchInFlight = AtomicBoolean(false)

    private val startFailureResetScheduled = AtomicBoolean(false)
    private val requestEpoch = AtomicLong()
    private data class ManualCountdown(val epoch: Long, val deadlineNanos: Long, val duration: Int) {
        val nextTick = AtomicReference<ScheduledFuture<*>?>()
    }
    private val manualTimer = ScheduledThreadPoolExecutor(1) { job ->
        Thread(job, "StartMode-Countdown-Timer").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val manualCountdown = AtomicReference<ManualCountdown?>()

    fun countdownRemainingSeconds(): Int = manualCountdown.get()?.let {
        StartCountdownPolicy.remainingSeconds(it.deadlineNanos, System.nanoTime())
    } ?: 0

    private fun invalidatePendingStart() {
        requestEpoch.incrementAndGet()
        manualCountdown.getAndSet(null)?.nextTick?.get()?.cancel(false)
    }

    private fun mayStart(player: PlayerHess?): Boolean {
        if (player == null) return true // Only trusted console/API callers supply null.
        return if (StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player)) {
            StartModeRoomPermissionSupport.require(player, StartModeRoomPermissionAction.START_GAME)
        } else StartModeRoomPermissionSupport.requireDelegatedPlayerAction(player, StartModeRoomPermissionAction.START_GAME)
    }

    fun cancelManualStart(player: PlayerHess? = null): Boolean {
        if (!mayStart(player)) return false
        val pending = manualCountdown.get() ?: return false
        if (!manualCountdown.compareAndSet(pending, null)) return false
        pending.nextTick.get()?.cancel(false)
        releaseFailedStartRequest(pending.epoch)
        currentModule()?.room?.call?.sendSystemMessage("开局倒计时已取消。")
        return true
    }


    fun onPlayerJoin(room: ServerRoom) {
        reevaluate(room, "playerJoin")
    }

    fun onPlayerLeave(room: ServerRoom) {
        reevaluate(room, "playerLeave")
    }

    fun onGameStart(room: ServerRoom) {
        invalidatePendingStart()
        Threads.closeTimeTask(CallTimeTask.AutoStartTask)
        Threads.closeTimeTask(CallTimeTask.StartConfirmationTask)
        Threads.closeTimeTask(CallTimeTask.PostGameReturnTask)
        lastStartRequestAt = 0L
        startOperationInProgress.set(false)
        startFailureResetScheduled.set(false)
        Log.clog("[StartMode] Game start confirmed. Auto-start countdown cleared.")
    }

    fun onGameOver(room: ServerRoom) {
        invalidatePendingStart()
        lastStartRequestAt = 0L
        startOperationInProgress.set(false)
        Threads.closeTimeTask(CallTimeTask.AutoStartTask)
        Threads.closeTimeTask(CallTimeTask.StartConfirmationTask)

        if (room.isGameOverResetInProgress() || StartModeGameOverReturnSupport.shouldSkipPostGameAutoStart()) {
            Threads.closeTimeTask(CallTimeTask.PostGameReturnTask)
            Log.clog("[StartMode] Post-game auto-start reevaluation skipped for forced server reset.")
            return
        }

        if (!StartModeGameOverReturnSupport.isSettling()) {
            schedulePostGameReevaluation(room, 1, "manualBattleRoomReturn")
            return
        }

        val postGameDelay = BattleRoomTransitionPolicy.watchdogDelaySeconds(
            StartModeGameOverReturnSupport.postGameSettleSeconds()
        ) + 1
        val presence = room.playerManage.buildPlayerPresenceSnapshot()
        if (postGameDelay > 0 && room.playerManage.countConnectedParticipants() > 0) {
            Log.clog("[StartMode] Auto-start reevaluation delayed until post-game return settles: ${postGameDelay}s")
            schedulePostGameReevaluation(room, postGameDelay, "postGameReturn")
            return
        }

        reevaluate(room, "gameOver")
    }

    fun onBattleRoomLobbyRecoveryCompleted(room: ServerRoom) {
        schedulePostGameReevaluation(room, 0, "battleRoomLobbyRecoveryReady")
    }

    fun isStartOperationInProgress(): Boolean = startOperationInProgress.get()

    fun onEngineStartPacketRejected(room: ServerRoom, reason: String) {
        invalidatePendingStart()
        lastStartRequestAt = 0L
        startOperationInProgress.set(false)
        Threads.closeTimeTask(CallTimeTask.StartConfirmationTask)
        Log.error("[StartMode] Engine START_GAME packet rejected by the server bridge: reason=$reason")
        if (!startFailureResetScheduled.compareAndSet(false, true)) {
            return
        }
        Threads.newThreadCore {
            try {
                room.gr()
            } finally {
                startFailureResetScheduled.set(false)
            }
        }
    }

    private fun schedulePostGameReevaluation(room: ServerRoom, delaySeconds: Int, reason: String) {
        runCatching { Threads.closeTimeTask(CallTimeTask.PostGameReturnTask) }
            .onFailure { error ->
                Log.warn("[StartMode] Failed to replace post-game auto-start reevaluation: reason=$reason", error)
            }
        runCatching {
            Threads.newCountdown(CallTimeTask.PostGameReturnTask, delaySeconds, TimeUnit.SECONDS) {
                if (!room.isStartGame) {
                    reevaluate(room, reason)
                }
            }
        }.onFailure { error ->
            Log.warn("[StartMode] Failed to schedule post-game auto-start reevaluation: reason=$reason", error)
        }
    }

    /**
     * START-FREEZE: run [requestStart] OFF the Netty worker thread.
     *
     * Why this exists (measured on the live host): `requestStart` synchronously calls
     * `suspendMainThreadOperations { ... multiplayerStart() }`, which blocks the CALLING thread
     * until the game thread finishes initialising the map (up to 30s, plus 30s more if the
     * operation had already begun). The live stack proves the caller is a Netty worker:
     *
     *   [epollEventLoopGroup-3-1] [StartMode] Failed to start game (manual):
     *     ...ClientCommands.requestStart(ClientCommands.kt:1469)
     *     ...GameVersionServer.receiveChat(GameVersionServer.kt:324)
     *     ...NewServerHandler.channelRead0(NewServerHandler.kt:56)   <- Netty worker
     *
     * And all three production instances run `nettyWorkerThreadsOverride = 1`, i.e. a SINGLE
     * worker for every player's every connection. While it is blocked, nobody's packets are read:
     * that is the reported "latency stops updating, chat is unavailable, others cannot join, and
     * those who got in first cannot act" -- one blocked thread explains every symptom, and a
     * bigger map simply means a longer `multiplayerStart()`.
     *
     * Both call sites ignore the return value: `CommandHandler.java:112` invokes
     * `command.runner.accept(...)` whose return type is void, and the START_GAME branch in
     * `HeadlessTypeConnect` never reads it either. So dispatching asynchronously changes nothing
     * for THOSE callers. Note the value is NOT unused in general -- the auto-start path
     * (requestStart called with trigger="auto") does consume it; that path is left synchronous
     * on purpose, so behaviour there is unchanged.
     *
     * Re-entrancy is already handled: [requestStart] CASes [startOperationInProgress] at its top.
     *
     * ⚠️ Note this does NOT go through `Threads.newThreadOther`: that pool's rejection handler is
     * CALLER-RUNS, so a saturated pool would run the task inline on the Netty worker -- precisely
     * the freeze being fixed, and silently. A dedicated thread is used instead.
     */
    fun requestStartOffNetworkThread(player: PlayerHess?, trigger: String): Boolean {
        /*
         * A1-01 (independent audit): reserve the dispatch slot ATOMICALLY.
         *
         * A plain `startOperationInProgress.get()` check here was check-then-act. The real CAS
         * happens inside requestStart, i.e. inside the spawned thread, and only after
         * enforceLobbyRules / presence snapshot / mode validation / map checks. So N concurrent
         * /start (or START_GAME) packets arriving before any of them wins that CAS would each pass
         * the check and each spawn a thread -- measured at 200 threads for 200 requests. Remote
         * clients could therefore drive unbounded thread creation (>=512 KB stack each under
         * -Xss512k).
         *
         * Now the slot is reserved with its own CAS before any thread exists, and released in a
         * finally inside the task. requestStart still CASes startOperationInProgress, so that
         * remains the correctness guard against a double start; this one only bounds dispatch.
         */
        if (!startDispatchInFlight.compareAndSet(false, true)) {
            if (trigger != "auto") {
                runCatching { player?.sendSystemMessage(Data.i18NBundle.getinput("start.settling")) }
            }
            return false
        }

        val task = Runnable {
            try {
                runCatching { requestStart(player, trigger) }
                    .onFailure { error ->
                        Log.error("[StartMode] Async start request failed ($trigger)", error)
                    }
            } finally {
                // Always release, including on failure, or a single bad request would wedge
                // dispatch until the process restarted.
                startDispatchInFlight.set(false)
            }
        }

        /*
         * Use a DEDICATED thread rather than Threads.newThreadOther.
         *
         * `Threads.newThreadOther` cannot be used here: GetNewThreadPool.getNewFixedThreadPool
         * installs a CALLER-RUNS rejection handler (GetNewThreadPool.kt:82-88), so when the
         * bounded "Other-" pool (2 threads / queue 96 in pureStartMode) is saturated, `execute`
         * silently runs the task ON THE CALLING THREAD -- which is the Netty worker. That would
         * re-introduce the exact server-wide freeze this method exists to prevent, at the worst
         * possible moment, and it would do so without any exception for us to catch. The verifier
         * reproduced this independently with the pool's exact configuration.
         *
         * A one-shot daemon thread makes the guarantee unconditional: the network thread is never
         * blocked, whatever the pool is doing. The dispatch CAS above bounds it to one at a time.
         */
        return runCatching {
            Thread(task, "StartMode-Start-Request").apply {
                isDaemon = true
                start()
            }
            true
        }.getOrElse { error ->
            // Thread creation itself failed (native thread exhaustion). Release the dispatch slot
            // here, because the task's own finally never ran -- otherwise dispatch would stay
            // wedged for the life of the process.
            startDispatchInFlight.set(false)
            Log.error("[StartMode] Failed to dispatch start request off the network thread ($trigger)", error)
            false
        }
    }

    /**
     * 仅供单测：把派发/开局标志恢复到"空闲"状态。
     *
     * 这两个标志是**进程级单例**，而单测在同一 JVM 内连续跑多个用例 ——
     * 上一个用例派发的线程可能还没跑完（`requestStart` 在无模块时立即返回 false，但线程调度有延迟），
     * 于是下一个用例看到标志仍被占用 ⇒ 全部被拒（实测 `accepted=0 rejected=24`）。
     * 那是**用例间污染**，不是生产缺陷：生产里同一时刻本就只该有一个开局请求在飞。
     */
    internal fun resetDispatchForTest() {
        invalidatePendingStart()
        startDispatchInFlight.set(false)
        startOperationInProgress.set(false)
        lastStartRequestAt = 0L
    }

    fun requestStart(player: PlayerHess? = null, trigger: String = "manual"): Boolean {
        val module = currentModule()
        if (module == null) {
            if (trigger != "auto") {
                player?.sendSystemMessage("Headless module is not ready. Please try again later.")
            }
            Log.warn("[StartMode] Start requested before headless module was ready ($trigger)")
            return false
        }
        val room = module.room

        if (!mayStart(player)) return false
        if (isStartOperationInProgress()) {
            player?.sendSystemMessage("开局正在倒计时或等待引擎确认，请勿重复点击。")
            return false
        }

        val settling = StartModeGameOverReturnSupport.isSettling()
        val resetInProgress = room.isGameOverResetInProgress()
        val battleRoomTransitionInProgress = room.isBattleRoomTransitionInProgress()
        if (GameOverSettlePolicy.shouldBlockStart(
                settling,
                resetInProgress,
                battleRoomTransitionInProgress
            )
        ) {
            when {
                settling -> player?.sendSystemMessage(player.i18NBundle.getinput("start.settling"))
                resetInProgress -> player?.sendSystemMessage("Room reset is in progress. Please start again later.")
                else -> player?.sendSystemMessage(player.i18NBundle.getinput("battleRoom.transitionInProgress"))
            }
            return false
        }
        val lobbySyncCooldownRemainingMillis = room.lobbyPlayerSyncCooldownRemainingMillis()
        if (lobbySyncCooldownRemainingMillis > 0L) {
            if (trigger != "auto") {
                player?.sendSystemMessage("战役室正在同步大厅状态 请稍后再开始")
            }
            Log.debug(
                "[StartMode] Start request delayed until battle-room lobby sync cooldown expires: " +
                    "trigger=$trigger, remainingMs=$lobbySyncCooldownRemainingMillis"
            )
            return false
        }
        if (room.isBattleRoomLobbyRecoveryInProgress()) {
            if (trigger != "auto") {
                player?.sendSystemMessage("战役室正在恢复大厅状态 请稍后再开始")
            }
            Log.debug("[StartMode] Start request blocked by battle-room lobby recovery: trigger=$trigger")
            return false
        }
        if (room.isStartGame) {
            player?.sendSystemMessage(player.i18NBundle.getinput("err.startGame"))
            return false
        }
        val now = Time.concurrentMillis()
        if (now - lastStartRequestAt < START_REQUEST_DEBOUNCE_MILLIS) {
            return false
        }

        StartModeLobbySupport.enforceLobbyRules()

        val presence = room.playerManage.buildPlayerPresenceSnapshot()
        val occupantCount = room.playerManage.countConnectedParticipants()
        val reservedOccupantCount = room.playerManage.countReservedParticipants()
        val startMinPlayerSize = Data.configServer.startMinPlayerSize
        if (startMinPlayerSize != -1 && occupantCount < startMinPlayerSize) {
            player?.sendSystemMessage(player.i18NBundle.getinput("start.playerNo", startMinPlayerSize))
            return false
        }

        val autoStartMinPlayerSize = Data.configServer.autoStartMinPlayerSize
        if (trigger == "auto" && autoStartMinPlayerSize != -1 && occupantCount < autoStartMinPlayerSize) {
            return false
        }

        if (reservedOccupantCount > Data.configServer.maxPlayer) {
            val message = "Current room player count exceeds maxPlayer=${Data.configServer.maxPlayer}. Reduce humans or AI before starting."
            player?.sendSystemMessage(message)
            room.call.sendSystemMessage(message)
            return false
        }

        validateModeState(room)?.let { failure ->
            player?.sendSystemMessage(failure)
            if (trigger == "auto") {
                room.call.sendSystemMessage(failure)
            }
            return false
        }

        if (room.maps.mapType != GameMaps.MapType.DefaultMap) {
            val mapData = room.maps.mapData
            if (mapData == null) {
                val message = "Custom map is not loaded. Re-select the map before starting."
                player?.sendSystemMessage(message)
                if (trigger == "auto") {
                    room.call.sendSystemMessage(message)
                }
                return false
            }

        }

        if (!startOperationInProgress.compareAndSet(false, true)) {
            /*
             * Another start is already in flight. This used to return silently, which the verifier
             * flagged: a player double-clicking Start saw nothing at all. Give visible feedback.
             * (Auto-start passes player == null, so it stays silent by design.)
             */
            if (trigger != "auto") {
                runCatching { player?.sendSystemMessage(Data.i18NBundle.getinput("start.settling")) }
            }
            return false
        }

        /*
         * START-FREEZE: stamp the debounce time HERE, inside the CAS window, not further down.
         *
         * It used to be written after the CAS (and after map validation). While every request ran
         * on the single Netty worker they were serialised, so the debounce held. Now that requests
         * can arrive on different threads, two concurrent /start calls could both pass the check at
         * the top and only then race the CAS -- the verifier measured exactly that
         * (debounce_rejected=0, cas_losers=1). The CAS still prevents a double start, but the
         * debounce had degraded to advisory. Writing it while holding the CAS flag restores it.
         */
        val epoch = requestEpoch.incrementAndGet()
        lastStartRequestAt = now
        val lifecycle = room.lobbyLifecycleGeneration()
        val config = Data.configServer
        val startConfig = Data.configStartMode
        val connection = player?.con
        val startMapType = room.maps.mapType
        val startMapName = room.maps.mapName
        val startMapRuntimeFile = room.maps.mapData?.runtimeFileName
        if (startMapType != GameMaps.MapType.DefaultMap) {
            val packetBuildError = runCatching {
                val packet = NetStaticData.RwHps.abstractNetPacket.getCustomStartGamePacket(room.maps)
                require(packet.bytes.isNotEmpty()) { "custom_start_packet_empty" }
            }.exceptionOrNull()
            if (packetBuildError != null) {
                releaseFailedStartRequest(epoch)
                Log.error("[StartMode] Failed to build custom START_GAME before engine start", packetBuildError)
                val message = buildMapLoadFailureMessage(room.maps.mapName, packetBuildError)
                player?.sendSystemMessage(message)
                room.call.sendSystemMessage(message)
                return false
            }
        }

        Threads.closeTimeTask(CallTimeTask.AutoStartTask)
        Threads.closeTimeTask(CallTimeTask.PlayerAfkTask)
        Threads.closeTimeTask(CallTimeTask.StartConfirmationTask)

        fun invalidReason(): String? {
            if (requestEpoch.get() != epoch || room.lobbyLifecycleGeneration() != lifecycle) return "房间代次已变化"
            if (currentModule() !== module || room.isStartGame || room.isGameOverResetInProgress() ||
                room.isLobbyPlayerSyncBlocked() || StartModeGameOverReturnSupport.isSettling()) return "房间不再处于可开局大厅"
            if (Data.configServer !== config || Data.configStartMode !== startConfig) return "配置已变化"
            if (room.maps.mapType != startMapType || room.maps.mapName != startMapName ||
                room.maps.mapData?.runtimeFileName != startMapRuntimeFile) return "地图已变化"
            if (player != null && (connection == null || player.con !== connection ||
                    !room.playerManage.isConnectedHumanPlayer(player) || room.playerManage.getPlayer(player.index) !== player)) return "发起人已断线或更换连接"
            if (!mayStart(player)) return "发起人的开局权限已撤回"
            val participants = room.playerManage.countConnectedParticipants()
            if (participants < config.startMinPlayerSize.coerceAtLeast(0) || room.playerManage.countReservedParticipants() > config.maxPlayer) return "参战人数不再满足条件"
            if (trigger == "auto" && (config.autoStartMinPlayerSize < 0 || participants < config.autoStartMinPlayerSize)) return "自动开局条件已变化"
            return validateModeState(room, prepareLobby = false)
        }

        fun commitStart(): Boolean = runCatching {
            check(invalidReason() == null) { invalidReason() ?: "start_cancelled" }
            val succeeded = module.gameFunction.suspendMainThreadOperations {
                check(invalidReason() == null) { invalidReason() ?: "start_cancelled" }
                room.capturePreGameLobbyState()
                StartModeSurvivalSupport.prepareCoreTeamsBeforeStart(room)
                StartModeGameOptionSupport.applyEffectiveNoNukesBeforeStart(module)
                module.gameScriptMultiPlayer.multiplayerStart()
                // prepare occurs before the next simulation step; native objects are not retained.
            }
            check(succeeded) { "game_thread_start_operation_failed" }
            if (requestEpoch.get() != epoch) return@runCatching room.isStartGame
            if (room.isStartGame) {
                startOperationInProgress.set(false)
                Threads.closeTimeTask(CallTimeTask.StartConfirmationTask)
            } else scheduleStartConfirmation(room, player, trigger, epoch)
            true
        }.getOrElse { error ->
            releaseFailedStartRequest(epoch)
            Log.error("[StartMode] Failed to start game ($trigger)", error)
            room.call.sendSystemMessage("开局未完成，请检查地图、人数和房间状态后重试。")
            false
        }

        invalidReason()?.let { reason ->
            releaseFailedStartRequest(epoch)
            player?.sendSystemMessage("无法开局：$reason")
            return false
        }
        val delay = if (trigger == "auto") 0 else config.manualStartCountdownSeconds.coerceIn(0, 300)
        if (delay == 0) return commitStart()
        val pending = ManualCountdown(epoch, System.nanoTime() + TimeUnit.SECONDS.toNanos(delay.toLong()), delay)
        manualCountdown.set(pending)
        return runCatching {
            room.call.sendSystemMessage("游戏将在 $delay 秒后开始。输入 .startcancel 可取消。")
            fun tick() {
                if (manualCountdown.get() !== pending) return
                try {
                    val invalid = invalidReason()
                    if (invalid != null) {
                        if (manualCountdown.compareAndSet(pending, null)) {
                            releaseFailedStartRequest(epoch)
                            room.call.sendSystemMessage("开局倒计时已取消：$invalid")
                        }
                        return
                    }
                    val nanos = pending.deadlineNanos - System.nanoTime()
                    if (nanos > 0L) {
                        val remaining = StartCountdownPolicy.remainingSeconds(pending.deadlineNanos, System.nanoTime())
                        room.call.sendSystemMessage("距离开局还有 $remaining 秒。")
                        pending.nextTick.set(manualTimer.schedule({ tick() }, minOf(nanos, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS))
                    } else if (manualCountdown.compareAndSet(pending, null)) {
                        // Never run engine startup on the timer's shared worker.
                        Thread({ commitStart() }, "StartMode-Start-Commit").apply { isDaemon = true; start() }
                    }
                } catch (error: Exception) {
                    if (manualCountdown.compareAndSet(pending, null)) releaseFailedStartRequest(epoch)
                    Log.warn("[StartMode] Countdown failed; start cancelled", error)
                }
            }
            pending.nextTick.set(manualTimer.schedule({ tick() }, minOf(TimeUnit.SECONDS.toNanos(1), pending.deadlineNanos - System.nanoTime()).coerceAtLeast(0L), TimeUnit.NANOSECONDS))
            true
        }.getOrElse { error ->
            manualCountdown.compareAndSet(pending, null)
            releaseFailedStartRequest(epoch)
            Log.error("[StartMode] Cannot schedule manual start countdown", error)
            false
        }
    }

    private fun scheduleStartConfirmation(room: ServerRoom, player: PlayerHess?, trigger: String, epoch: Long) {
        runCatching {
            Threads.newCountdown(
                CallTimeTask.StartConfirmationTask,
                START_CONFIRMATION_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            ) {
                if (requestEpoch.get() != epoch) return@newCountdown
                if (room.isStartGame || StartModeGameOverReturnSupport.isPlaying()) {
                    startOperationInProgress.set(false)
                    return@newCountdown
                }
                if (!startOperationInProgress.compareAndSet(true, false)) {
                    return@newCountdown
                }
                lastStartRequestAt = 0L
                val message = "Start request was not accepted by the game core. You can try again."
                Log.warn("[StartMode] Start confirmation timed out after ${START_CONFIRMATION_TIMEOUT_SECONDS}s: trigger=$trigger")
                player?.sendSystemMessage(message)
                room.call.sendSystemMessage(message)
            }
        }.onFailure { error ->
            releaseFailedStartRequest(epoch)
            Log.error("[StartMode] Failed to schedule start confirmation watchdog", error)
        }
    }

    private fun releaseFailedStartRequest(epoch: Long) {
        if (!requestEpoch.compareAndSet(epoch, epoch + 1)) return
        manualCountdown.getAndSet(null)?.nextTick?.get()?.cancel(false)
        lastStartRequestAt = 0L
        startOperationInProgress.set(false)
        Threads.closeTimeTask(CallTimeTask.StartConfirmationTask)
    }

    fun buildAutoStartDiagnostics(room: ServerRoom? = currentModule()?.room): Map<String, Any?> {
        val module = currentModule()
        if (module == null || room == null) {
            return mapOf(
                "moduleReady" to false,
                "roomStarted" to false,
                "autoStartEnabled" to (Data.configServer.autoStartMinPlayerSize != -1),
                "autoStartTaskArmed" to Threads.containsTimeTask(CallTimeTask.AutoStartTask),
                "startConfirmationTaskArmed" to Threads.containsTimeTask(CallTimeTask.StartConfirmationTask),
                "canAutoStartNow" to false,
                "blockedReason" to "headless_not_ready"
            )
        }

        val presence = room.playerManage.buildPlayerPresenceSnapshot()
        val occupantCount = room.playerManage.countConnectedParticipants()
        val reservedOccupantCount = room.playerManage.countReservedParticipants()
        val autoStartMinPlayerSize = Data.configServer.autoStartMinPlayerSize
        val mapEngineDiagnostics = mapEngineDiagnostics()
        val modeStateResult = validateModeState(room, prepareLobby = false)
        val blockedReason = when {
            room.isGameOverResetInProgress() -> "gameover_reset_in_progress"
            StartModeGameOverReturnSupport.isSettling() -> "gameover_settling"
            room.isBattleRoomTransitionInProgress() -> "battle_room_transition_in_progress"
            room.isBattleRoomLobbyRecoveryInProgress() -> "battle_room_lobby_recovery"
            room.isStartGame -> "room_already_ingame"
            startOperationInProgress.get() -> "start_confirmation_pending"
            autoStartMinPlayerSize == -1 -> "auto_start_disabled"
            occupantCount < autoStartMinPlayerSize -> "below_auto_start_min_players"
            reservedOccupantCount > Data.configServer.maxPlayer -> "above_max_players"
            modeStateResult != null -> modeStateResult
            room.maps.mapType != GameMaps.MapType.DefaultMap && room.maps.mapData == null -> "custom_map_not_loaded"
            else -> null
        }

        return mapOf(
            "moduleReady" to true,
            "roomStarted" to room.isStartGame,
            "battleRoomLobbyRecoveryInProgress" to room.isBattleRoomLobbyRecoveryInProgress(),
            "occupantCount" to occupantCount,
            "reservedOccupantCount" to reservedOccupantCount,
            "connectedHumanCount" to presence.connectedHumanCount,
            "graceProtectedHumanCount" to presence.graceProtectedHumanCount,
            "reconnectGraceHumanCount" to presence.reconnectGraceHumanCount,
            "startMinPlayerSize" to Data.configServer.startMinPlayerSize,
            "autoStartMinPlayerSize" to autoStartMinPlayerSize,
            "maxPlayer" to Data.configServer.maxPlayer,
            "manualStartCountdownSeconds" to Data.configServer.manualStartCountdownSeconds,
            "manualStartCountdownRemainingSeconds" to countdownRemainingSeconds(),
            "startOperationInProgress" to isStartOperationInProgress(),
            "autoStartEnabled" to (autoStartMinPlayerSize != -1),
            "autoStartTaskArmed" to Threads.containsTimeTask(CallTimeTask.AutoStartTask),
            "startConfirmationTaskArmed" to Threads.containsTimeTask(CallTimeTask.StartConfirmationTask),
            "countdownSeconds" to Data.configServer.autoStartCountdownSeconds.coerceAtLeast(0),
            "debounceActive" to (Time.concurrentMillis() - lastStartRequestAt < START_REQUEST_DEBOUNCE_MILLIS),
            "startOperationInProgress" to startOperationInProgress.get(),
            "currentMapName" to room.maps.mapName,
            "currentMapType" to room.maps.mapType.name,
            "customMapLoaded" to (room.maps.mapType == GameMaps.MapType.DefaultMap || room.maps.mapData != null),
            "mapEngineReady" to mapEngineDiagnostics.ready,
            "mapEngineDiagnosticsAvailable" to mapEngineDiagnostics.available,
            "mapEngineDiagnosticsError" to mapEngineDiagnostics.error,
            "canAutoStartNow" to (blockedReason == null),
            "blockedReason" to blockedReason
        )
    }

    fun buildAutoStartProjection(
        projectedJoinedExpectedHexIds: List<String>,
        room: ServerRoom? = currentModule()?.room
    ): Map<String, Any?> {
        val module = currentModule()
        if (module == null || room == null) {
            return mapOf(
                "moduleReady" to false,
                "managedMatchAutoStart" to false,
                "projectedJoinedExpectedCount" to 0,
                "projectedOccupantCount" to 0,
                "wouldTriggerStartRequest" to false,
                "blockedReason" to "headless_not_ready"
            )
        }

        val presence = room.playerManage.buildPlayerPresenceSnapshot()
        // No externally prepared match can exist in this build, so the projection always
        // degenerates to the no-managed-match result.
        return mapOf(
            "moduleReady" to true,
            "managedMatchAutoStart" to false,
            "projectedJoinedExpectedCount" to 0,
            "projectedOccupantCount" to room.playerManage.countConnectedParticipants(),
            "wouldTriggerStartRequest" to false,
            "blockedReason" to "no_managed_match"
        )
    }

    private fun reevaluate(room: ServerRoom, reason: String) {
        if (room.isGameOverResetInProgress()) {
            Threads.closeTimeTask(CallTimeTask.AutoStartTask)
            return
        }

        if (StartModeGameOverReturnSupport.isSettling()) {
            Threads.closeTimeTask(CallTimeTask.AutoStartTask)
            return
        }

        if (room.isBattleRoomTransitionInProgress()) {
            Threads.closeTimeTask(CallTimeTask.AutoStartTask)
            return
        }

        if (room.isBattleRoomLobbyRecoveryInProgress()) {
            Threads.closeTimeTask(CallTimeTask.AutoStartTask)
            return
        }

        if (room.isStartGame) {
            Threads.closeTimeTask(CallTimeTask.AutoStartTask)
            return
        }

        if (startOperationInProgress.get()) {
            Threads.closeTimeTask(CallTimeTask.AutoStartTask)
            return
        }

        val autoStartMinPlayerSize = Data.configServer.autoStartMinPlayerSize
        if (autoStartMinPlayerSize == -1) {
            Threads.closeTimeTask(CallTimeTask.AutoStartTask)
            return
        }

        StartModeLobbySupport.enforceLobbyRules()

        val presence = room.playerManage.buildPlayerPresenceSnapshot()
        val occupantCount = room.playerManage.countConnectedParticipants()
        val reservedOccupantCount = room.playerManage.countReservedParticipants()
        if (
            occupantCount < autoStartMinPlayerSize
            || reservedOccupantCount > Data.configServer.maxPlayer
        ) {
            Threads.closeTimeTask(CallTimeTask.AutoStartTask)
            return
        }

        if (Threads.containsTimeTask(CallTimeTask.AutoStartTask)) {
            return
        }

        val countdownSeconds = Data.configServer.autoStartCountdownSeconds.coerceAtLeast(0)
        if (countdownSeconds == 0) {
            if (!requestStartOffNetworkThread(null, "auto")) {
                room.call.sendSystemMessage("Auto-start failed: room did not pass pre-start checks. Check player count, map and mode config.")
            }
            return
        }

        var remainingSeconds = countdownSeconds
        Log.clog("[StartMode] Auto-start countdown armed ($reason): $countdownSeconds seconds")
        Threads.newTimedTask(CallTimeTask.AutoStartTask, 0, 1, TimeUnit.SECONDS) {
            if (room.isGameOverResetInProgress()) {
                Threads.closeTimeTask(CallTimeTask.AutoStartTask)
                return@newTimedTask
            }

            if (StartModeGameOverReturnSupport.isSettling()) {
                Threads.closeTimeTask(CallTimeTask.AutoStartTask)
                return@newTimedTask
            }

            if (room.isBattleRoomTransitionInProgress()) {
                Threads.closeTimeTask(CallTimeTask.AutoStartTask)
                return@newTimedTask
            }

            if (room.isBattleRoomLobbyRecoveryInProgress()) {
                Threads.closeTimeTask(CallTimeTask.AutoStartTask)
                return@newTimedTask
            }

            if (room.isStartGame) {
                Threads.closeTimeTask(CallTimeTask.AutoStartTask)
                return@newTimedTask
            }

            if (startOperationInProgress.get()) {
                Threads.closeTimeTask(CallTimeTask.AutoStartTask)
                return@newTimedTask
            }

            StartModeLobbySupport.enforceLobbyRules(forceSync = false)
            val currentPresence = room.playerManage.buildPlayerPresenceSnapshot()
            val currentOccupantCount = room.playerManage.countConnectedParticipants()
            val currentReservedOccupantCount = room.playerManage.countReservedParticipants()
            if (
                currentOccupantCount < autoStartMinPlayerSize
                || currentReservedOccupantCount > Data.configServer.maxPlayer
            ) {
                Threads.closeTimeTask(CallTimeTask.AutoStartTask)
                return@newTimedTask
            }

            if (remainingSeconds <= 0) {
                Threads.closeTimeTask(CallTimeTask.AutoStartTask)
                if (!requestStartOffNetworkThread(null, "auto")) {
                    room.call.sendSystemMessage("Auto-start failed: room did not pass pre-start checks. Check player count, map and mode config.")
                }
                return@newTimedTask
            }

            room.call.sendSystemMessage(Data.i18NBundle.getinput("auto.start", remainingSeconds))
            remainingSeconds--
        }
    }

    private fun validateModeState(room: ServerRoom, prepareLobby: Boolean = true): String? {
        if (Data.configStartMode.enableSurvivalMapMode) {
            val layout = StartModeSurvivalLayoutSupport.layout(room)
            val missingAiSlots = layout.aiSlots.filter { slot ->
                room.playerManage.getPlayer(slot.playerIndex)?.isAi != true
            }
            if (missingAiSlots.isNotEmpty()) {
                return "Survival reserved AI is not ready: ${StartModeSurvivalLayoutSupport.reservedAiDescription(room)}. Please wait and start again."
            }

            if (prepareLobby) StartModeSurvivalSupport.prepareLobbySurvivalAi(room = room)

            val declaredHumanSlots = layout.humanSlots
                .map { it.playerIndex }
                .filter { it in 0 until Data.configServer.maxPlayer && it !in layout.reservedPlayerIndexes }
                .toSet()
            val occupants = snapshotPlayers(room)
            val humans = occupants.filter { !it.isAi }
            if (layout.hasDeclaredHumanSlots && humans.size > declaredHumanSlots.size) {
                return "Survival map only declares ${declaredHumanSlots.size} human start slot(s): ${StartModeSurvivalLayoutSupport.humanSlotsDescription(room)}. Reduce players or choose a map with more human slots."
            }
            if (layout.hasDeclaredHumanSlots && humans.any { it.index !in declaredHumanSlots }) {
                return "Survival human start slots are not settled yet: ${StartModeSurvivalLayoutSupport.humanSlotsDescription(room)}. Please wait and start again."
            }

            for (occupant in occupants) {
                if (occupant.isAi) {
                    if (layout.hasDeclaredHumanSlots && occupant.index in declaredHumanSlots) {
                        return "Survival human start slot is occupied by AI. Please wait for lobby layout repair before starting."
                    }
                    val aiAllyGroup = StartModeSurvivalLayoutSupport.aiAllyGroupForIndex(occupant.index, room)
                    if (aiAllyGroup != null && occupant.team != aiAllyGroup) {
                        return "Survival AI layout is not stable: ${StartModeSurvivalLayoutSupport.reservedAiDescription(room)}. Please wait and start again."
                    }
                } else if (occupant.team != layout.humanAllyGroup) {
                    return "Survival humans must be in ally ${layout.humanAllyGroup}. Please wait and start again."
                }
            }
        }

        if (Data.configStartMode.enableFreeForAllMode) {
            val usedTeams = HashSet<Int>()
            for (occupant in snapshotPlayers(room)) {
                if (!usedTeams.add(occupant.team)) {
                    return "Free-for-all mode requires unique teams for every player. Current team layout is not stable."
                }
            }
        }

        return null
    }

    private fun buildMapLoadFailureMessage(mapName: String, error: Throwable): String {
        val detail = error.message?.trim().orEmpty()
        return if (detail.isBlank()) {
            "Map load failed for $mapName. Check that the map file exists and is complete."
        } else {
            "Map load failed: $detail. Check the map file in maps directory and retry."
        }
    }

    private fun snapshotPlayers(room: ServerRoom): List<PlayerHess> {
        val players = ArrayList<PlayerHess>()
        room.playerManage.playerGroup.eachAll { player ->
            if (player.team != StartModeLobbySupport.SPECTATOR_TEAM && (player.isAi || room.playerManage.isConnectedHumanPlayer(player))) {
                players += player
            }
        }
        return players
    }

    /**
     * The embedded map engine is optional in the pure Start distribution. Diagnostics are
     * informational, so probing it must not make the auto-start diagnostics fail to report.
     */
    private fun mapEngineDiagnostics(): MapEngineDiagnostics {
        val readiness = StartModeReadinessSupport.mapEngineReadiness()
        return MapEngineDiagnostics(
            available = readiness.available,
            ready = readiness.ready,
            error = readiness.error
        )
    }

    private fun currentModule() = if (HeadlessModuleManage.initHPS()) HeadlessModuleManage.hps else null

    private data class MapEngineDiagnostics(
        val available: Boolean,
        val ready: Boolean,
        val error: String?
    )
}
