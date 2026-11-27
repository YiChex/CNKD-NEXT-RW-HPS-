/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.dependent.redirections.game.clas

import com.corrodinggames.rts.game.i
import net.rwhps.asm.api.listener.RedirectionListener
import net.rwhps.server.plugin.internal.headless.support.GameLoopLivenessPolicy
import net.rwhps.server.util.log.Log
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs the engine-owned task queue before the headless lobby can return early
 * because no map has been loaded yet.
 */
object GameLoopOperationDrain : RedirectionListener {
    private const val DEFAULT_STALE_AFTER_MILLIS = 30_000L
    /** P0-STALL: reflection is a last resort; only re-attempt it this often. */
    private const val QUEUE_RESOLVE_RETRY_INTERVAL_MILLIS = 1_000L
    /** P0-STALL: identical resolve failures are reported at most once per interval. */
    private const val QUEUE_RESOLVE_LOG_INTERVAL_MILLIS = 60_000L
    /** A1-08: same idea for per-operation drain failures, which used to log every frame. */
    private const val QUEUE_DRAIN_LOG_INTERVAL_MILLIS = 60_000L
    /**
     * P0-STALL: how long the loop may stay tick-less after the server started before we call it
     * dead rather than starting. 30s matches the stale threshold and the operation timeout.
     */
    private const val DEFAULT_LOOP_NEVER_STARTED_GRACE_MILLIS = 30_000L
    /**
     * 跨加载器共享的 tick 状态键（System properties）。
     *
     * 为什么需要：本类在 JVM 中**有两份**（App + 游戏加载器），静态字段各自独立。
     * 写方 `FPSSleepRedirections` 在 App 加载器，读方 `GameFunction` 在游戏加载器，
     * 导致读方永远看到初始值 0。System 由 bootstrap 加载器持有，两个副本共享同一份。
     */
    private const val CROSS_LOADER_TICK_AT_KEY = "cnkd.headless.gameLoop.lastTickAtMillis"
    private const val CROSS_LOADER_TICK_THREAD_KEY = "cnkd.headless.gameLoop.lastTickThreadName"

    /**
     * P0-STALL: when the game loop was last expected to start ticking, i.e. the moment
     * [resetHealth] ran from [net.rwhps.server.plugin.internal.headless.inject.core.link.LinkGameNet.startHeadlessServer].
     *
     * This is what separates the two otherwise identical "never ticked" situations:
     *  - the loop is still starting up (only a moment has passed), versus
     *  - the loop died before its first tick or was never started at all.
     * Before this existed, both looked like NOT_OBSERVED and the caller just waited.
     */
    @Volatile
    private var loopExpectedSinceMillis = 0L
    /** P0-STALL: emit the "loop is dead" line at most once per this interval. */
    private const val DEAD_LOOP_LOG_INTERVAL_MILLIS = 60_000L
    @Volatile
    private var lastDeadLoopLogAtMillis = 0L

    @Volatile
    private var currentEngineQueue: ConcurrentLinkedQueue<*>? = null

    /**
     * P0-STALL: the queue handed over by [net.rwhps.server.plugin.internal.headless.inject.core.GameFunction],
     * which obtains it by plain field access (`(GameEngine.gameEngine as? i)?.k`) and therefore never
     * needs reflection. Authoritative over [currentEngineQueue]; reflection is only used if this is null.
     */
    @Volatile
    private var registeredEngineQueue: ConcurrentLinkedQueue<*>? = null
    @Volatile
    private var lastQueueResolveAttemptAtMillis = 0L
    @Volatile
    private var lastQueueResolveLogAtMillis = 0L
    @Volatile
    private var lastQueueResolveReason = ""
    private val queueResolveFailureCount = AtomicLong()
    private val loopTickCount = AtomicLong()
    private val queueDrainFailureCount = AtomicLong()
    /** A1-08: rate-limit for the per-operation drain failure ERROR (first failure always logs). */
    @Volatile
    private var lastQueueDrainLogAtMillis = 0L
    @Volatile
    private var lastLoopTickAtMillis = 0L
    @Volatile
    private var lastLoopThreadName = ""
    @Volatile
    private var lastQueueDrainError = ""

    override fun invoke(obj: Any, desc: String, vararg args: Any?) {
        val engine = obj as? i ?: return
        recordLoopTick()
        if (currentEngineQueue !== engine.k) {
            currentEngineQueue = engine.k
        }
        drain(engine.k)
    }

    internal fun recordLoopTick() {
        val now = System.currentTimeMillis()
        lastLoopTickAtMillis = now
        lastLoopThreadName = Thread.currentThread().name
        loopTickCount.incrementAndGet()
        /*
         * CROSS-LOADER FIX: 这个类在 JVM 中有两份（App 加载器 + 游戏加载器）。
         *
         * 实测（2026-10-21，jcmd VM.class_hierarchy + 自写 agent 探针）：
         *   - FPSSleepRedirections（App 加载器）每帧调本方法 → 只递增 App 副本
         *   - GameFunction（游戏加载器）调 healthSnapshot() → 只读游戏副本
         *   ⇒ 游戏副本 loopTickCount 恒 0，导致 GameFunction 每次操作都误判
         *     "game loop has not ticked" 并打一条 WARN（实测 913 次操作 → 913 条 WARN）。
         *
         * 用 System properties 做跨加载器共享：System 由 bootstrap 加载器持有，
         * 两个副本访问的是同一份 Properties 实例。
         *
         * 只写两个标量（epoch millis + 线程名），每帧一次，开销可忽略。
         */
        publishCrossLoaderTick(now, lastLoopThreadName)
    }

    private fun publishCrossLoaderTick(nowMillis: Long, threadName: String) {
        try {
            System.setProperty(CROSS_LOADER_TICK_AT_KEY, nowMillis.toString())
            System.setProperty(CROSS_LOADER_TICK_THREAD_KEY, threadName)
        } catch (_: Throwable) {
            // 属性写入失败（SecurityManager 等）绝不能影响游戏循环。
        }
    }

    /**
     * 读取**跨加载器共享**的 tick 时间戳。
     *
     * 与 [healthSnapshot] 的区别：后者读本副本字段（游戏副本恒 0），本方法读
     * System properties，因此无论从哪个加载器调用都能拿到真实值。
     *
     * @return 最近一次 tick 的 epoch millis；从未 tick 过则为 0
     */
    internal fun crossLoaderLastTickAtMillis(): Long {
        return try {
            System.getProperty(CROSS_LOADER_TICK_AT_KEY)?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    /** 跨加载器共享的 tick 线程名；从未 tick 过则为空串。 */
    internal fun crossLoaderLastTickThreadName(): String {
        return try {
            System.getProperty(CROSS_LOADER_TICK_THREAD_KEY) ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * P0-STALL: called by GameFunction immediately after it successfully enqueued an operation,
     * i.e. from the one call site that has already proved the queue is usable. Idempotent and
     * safe to call from any thread. The drain prefers this reference so the fragile reflective
     * path in [resolveCurrentEngineQueue] is no longer load-bearing.
     */
    internal fun registerEngineQueue(queue: ConcurrentLinkedQueue<*>) {
        if (registeredEngineQueue !== queue) {
            registeredEngineQueue = queue
        }
        if (currentEngineQueue !== queue) {
            currentEngineQueue = queue
        }
    }

    internal fun invalidateCurrentEngineQueue() {
        currentEngineQueue = null
        registeredEngineQueue = null
    }

    internal fun resetHealth() {
        currentEngineQueue = null
        registeredEngineQueue = null
        lastQueueResolveAttemptAtMillis = 0L
        lastQueueResolveLogAtMillis = 0L
        lastQueueResolveReason = ""
        queueResolveFailureCount.set(0L)
        loopTickCount.set(0L)
        queueDrainFailureCount.set(0L)
        lastQueueDrainLogAtMillis = 0L
        lastLoopTickAtMillis = 0L
        lastDeadLoopLogAtMillis = 0L
        // P0-STALL: stamp the moment the loop became expected, so a loop that never starts
        // (or dies before its first tick) can be told apart from one that is merely starting.
        loopExpectedSinceMillis = System.currentTimeMillis()
        lastLoopThreadName = ""
        lastQueueDrainError = ""
    }

    internal data class HealthSnapshot(
        val state: GameLoopLivenessPolicy.State,
        val lastTickAtMillis: Long,
        val tickCount: Long,
        val threadName: String,
        val drainFailureCount: Long,
        val lastDrainError: String?
    ) {
        val observed: Boolean
            get() = state != GameLoopLivenessPolicy.State.NOT_OBSERVED

        val alive: Boolean
            get() = state == GameLoopLivenessPolicy.State.HEALTHY

        fun ageMillis(nowMillis: Long): Long? = GameLoopLivenessPolicy.ageMillis(lastTickAtMillis, nowMillis)
    }

    internal fun healthSnapshot(
        nowMillis: Long = System.currentTimeMillis(),
        staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS
    ): HealthSnapshot {
        val lastTick = lastLoopTickAtMillis
        return HealthSnapshot(
            state = GameLoopLivenessPolicy.evaluate(lastTick, nowMillis, staleAfterMillis),
            lastTickAtMillis = lastTick,
            tickCount = loopTickCount.get(),
            threadName = lastLoopThreadName,
            drainFailureCount = queueDrainFailureCount.get(),
            lastDrainError = lastQueueDrainError.ifBlank { null }
        )
    }

    /**
     * P0-STALL: is the game loop confirmed dead rather than merely starting up?
     *
     * The live incident (instance b013) showed the game-loop thread dying from an uncaught
     * `NullPointerException: this.bL is null` inside `updateAndRender` 36 seconds after startup,
     * after which nothing ever ticked again. Because `recordLoopTick()` only runs *on* the loop,
     * a dead loop looks exactly like a loop that has not started yet -- `NOT_OBSERVED` forever.
     *
     * [loopExpectedSinceMillis] is stamped by [resetHealth] (i.e. when `startHeadlessServer` runs),
     * so once [graceMillis] has elapsed with zero ticks the loop is conclusively dead: it cannot be
     * "still starting" that long after the server began listening.
     *
     * Note this deliberately does NOT try to restart the loop. mcsmanager runs these instances with
     * `autoRestart: false`, so exiting the process would turn a degraded instance into a permanently
     * dead one. Detecting and reporting loudly is the safe half; the operator decides on restart.
     */
    internal fun isLoopConfirmedDead(
        nowMillis: Long = System.currentTimeMillis(),
        graceMillis: Long = DEFAULT_LOOP_NEVER_STARTED_GRACE_MILLIS
    ): Boolean {
        val lastTick = lastLoopTickAtMillis
        if (lastTick > 0L) {
            /*
             * P2 (found by independent verification): a loop that RAN and then died used to return
             * false here forever, so the only symptom was silent 30s timeouts -- no dead-loop alert.
             * b013 happened to die 33s after startup, which is the only reason it matched the
             * "never ticked" branch. A long-running instance whose loop dies mid-match would have
             * gone unreported.
             *
             * A tick that stopped longer ago than the stale threshold is just as dead as one that
             * never happened, and this stays copy-local (both values are written by this copy).
             */
            return nowMillis - lastTick >= graceMillis
        }
        val expectedSince = loopExpectedSinceMillis
        if (expectedSince <= 0L) {
            return false
        }
        return nowMillis - expectedSince >= graceMillis
    }

    /**
     * P0-STALL: rate-limited "the game loop is dead" report, intended to be called from a scheduler
     * task that does NOT depend on the game loop (the Quartz heartbeat), since nothing on the loop
     * itself can report its own death.
     *
     * ⚠️ CLASSLOADER SPLIT (verified on the live host with `jcmd VM.class_hierarchy`): this class is
     * loaded TWICE. `GameStartInit` injects the `...redirections.game.clas` prefix into the game
     * loader, but `StartModeLatencyHeartbeatSupport` lives in `...headless.support` and therefore
     * calls the APP-loader copy. `resetHealth()` runs from `LinkGameNet`, which is game-loader, so
     * it stamps the GAME copy only -- the app copy's [loopExpectedSinceMillis] would stay 0 and
     * [isLoopConfirmedDead] would return false forever, making this whole alert dead code.
     *
     * Fix: this copy stamps its OWN observation time on first use, so it does not depend on a
     * timestamp written by the other copy. That is safe here because the app copy DOES receive
     * ticks: `FPSSleepRedirections` is app-loader and calls `recordLoopTick()` every frame while
     * the loop runs. So a live loop keeps `lastLoopTickAtMillis > 0` and this never false-alarms;
     * a loop that never ticked within the grace period is genuinely not running.
     */
    internal fun reportDeadLoopIfConfirmed(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (loopExpectedSinceMillis <= 0L) {
            // First observation on THIS copy: start the grace window here rather than trusting a
            // timestamp another classloader copy may have written.
            loopExpectedSinceMillis = nowMillis
            return false
        }
        if (!isLoopConfirmedDead(nowMillis)) {
            return false
        }
        if (nowMillis - lastDeadLoopLogAtMillis < DEAD_LOOP_LOG_INTERVAL_MILLIS) {
            return true
        }
        lastDeadLoopLogAtMillis = nowMillis
        /*
         * A1-04 (independent audit): the text used to hardcode "never ticked ... ticks=0" even when
         * the ran-then-died branch fired, which would have misled whoever read it. Distinguish the
         * two cases and quote the real numbers.
         */
        val lastTick = lastLoopTickAtMillis
        val detail = if (lastTick > 0L) {
            "the game thread last ticked ${nowMillis - lastTick}ms ago and has stopped"
        } else {
            "the game thread has NEVER ticked"
        }
        Log.error(
            "[Headless] GAME LOOP IS DEAD: $detail (ticks=${loopTickCount.get()}, " +
                "lastThread=${lastLoopThreadName.ifBlank { "none" }}). Every game-thread operation " +
                "will time out; the server accepts connections but cannot act on them. " +
                "A previous uncaught exception on the game-loop thread is the usual cause -- " +
                "check Log.txt for '[Error] Thread-'. Restart the instance to recover " +
                "(it will not restart itself)."
        )
        return true
    }

    /**
     * The Slick update callback has no engine receiver. Resolve the current
     * headless engine here so its queue is still serviced before lobby code can
     * hit the original no-map early return.
     */
    internal fun drainCurrentEngineQueue(callbackHost: Any?) {
        // P0-STALL: order of preference.
        //   1. registeredEngineQueue - proved usable by GameFunction, no reflection.
        //   2. currentEngineQueue    - set by the primary hook or a previous successful resolve.
        //   3. reflection            - legacy fallback, rate-limited and loudly logged on failure.
        var queue = registeredEngineQueue ?: currentEngineQueue
        if (queue == null) {
            queue = synchronized(this) {
                currentEngineQueue ?: resolveCurrentEngineQueue(callbackHost)?.also {
                    currentEngineQueue = it
                }
            }
        }
        if (queue == null) {
            // P0-STALL: this used to be a bare `?: return`, which made the whole incident
            // invisible in production. Never silent again.
            recordQueueResolveFailure("no engine queue available (registered=null, hook=null, reflection failed)")
            return
        }
        drain(queue)
    }

    private fun resolveCurrentEngineQueue(callbackHost: Any?): ConcurrentLinkedQueue<*>? {
        // P0-STALL: never spend a reflective resolve on every game frame once it has failed.
        val now = System.currentTimeMillis()
        if (now - lastQueueResolveAttemptAtMillis < QUEUE_RESOLVE_RETRY_INTERVAL_MILLIS) {
            return null
        }
        lastQueueResolveAttemptAtMillis = now

        val loader = callbackHost?.javaClass?.classLoader
        if (loader == null) {
            recordQueueResolveFailure(
                "callback host has no class loader (host=${callbackHost?.javaClass?.name ?: "null"})"
            )
            return null
        }
        return try {
            val gameEngineClass = Class.forName(
                "com.corrodinggames.rts.gameFramework.l",
                true,
                loader
            )
            val gameEngineAccessor = gameEngineClass.getDeclaredMethod("B").apply {
                isAccessible = true
            }
            val gameEngine = gameEngineAccessor.invoke(null)
            if (gameEngine == null) {
                recordQueueResolveFailure("com.corrodinggames.rts.gameFramework.l.B() returned null")
                return null
            }
            val queueField = findField(gameEngine.javaClass, "k")
            if (queueField == null) {
                recordQueueResolveFailure(
                    "field 'k' not found on ${gameEngine.javaClass.name}; " +
                        "expected com.corrodinggames.rts.game.i (l declares only 'dN')"
                )
                return null
            }
            queueField.isAccessible = true
            val queue = queueField.get(gameEngine) as? ConcurrentLinkedQueue<*>
            if (queue == null) {
                recordQueueResolveFailure(
                    "field 'k' on ${gameEngine.javaClass.name} is not a ConcurrentLinkedQueue"
                )
            }
            queue
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) {
                throw error
            }
            recordQueueResolveFailure("reflection failed: ${error.javaClass.name}: ${error.message}")
            null
        }
    }

    /**
     * P0-STALL: one bounded, rate-limited error line per distinct failure reason, so a
     * per-frame failure cannot flood the console while still being impossible to miss.
     */
    private fun recordQueueResolveFailure(reason: String) {
        val now = System.currentTimeMillis()
        val reasonChanged = reason != lastQueueResolveReason
        val intervalElapsed = now - lastQueueResolveLogAtMillis >= QUEUE_RESOLVE_LOG_INTERVAL_MILLIS
        if (!reasonChanged && !intervalElapsed) {
            queueResolveFailureCount.incrementAndGet()
            return
        }
        val repeated = if (reasonChanged) 0L else queueResolveFailureCount.getAndSet(0L)
        lastQueueResolveReason = reason
        lastQueueResolveLogAtMillis = now
        /*
         * FIX-LOG-LEVEL: 从 ERROR 降为 WARN。
         *
         * 实测（2026-10-21，1.1.20 包，端口 16501）：
         *   - 每次正常启动**必然**出现 2 条，且等 70 秒跨过 60 秒重报窗口后**仍是 2 条**（不增长）
         *   - 同期 loop.ticks=9856、loop.state=HEALTHY、drainFailures=0
         *   ⇒ 纯启动竞态，失败已自动停止，无功能影响。
         *
         * 用 ERROR 描述「每次启动必现且自动消失」的事件会造成告警疲劳，
         * 进而掩盖 :310 真正的 `GAME LOOP IS DEAD`（那条必须保持 ERROR）。
         *
         * 注意：这里保留 reason 细节，因为 `l.B() returned null` 与
         * `no engine queue available` 是**同一次失败的两个不同 reason**
         * （先反射取队列失败，再报无可用队列），所以一次启动固定 2 条。
         */
        Log.warn(
            "[Headless] Game-loop queue drain is NOT running: $reason " +
                "(repeated=$repeated, gameLoopThread=${lastLoopThreadName.ifBlank { "unknown" }})"
        )
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            val field = runCatching { current!!.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                return field
            }
            current = current.superclass
        }
        return null
    }

    internal fun drain(queue: ConcurrentLinkedQueue<*>) {
        while (true) {
            val operation = queue.poll() ?: return
            try {
                (operation as? Runnable)?.run() ?: error("game_loop_queue_entry_not_runnable")
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) {
                    throw error
                }
                val failures = queueDrainFailureCount.incrementAndGet()
                lastQueueDrainError = error.message?.take(240) ?: error::class.java.simpleName
                /*
                 * A1-08 (independent audit): this used to log unconditionally, per failed queue
                 * entry, per frame. Each line carries a stack trace and is ERROR level -- and
                 * ERROR is deliberately NOT capped by the LogCore WARN budget (R1), because
                 * genuine ERRORs must never be evicted. So a persistently failing operation would
                 * write a full stack every frame and could fill the 8 MB active log in seconds,
                 * which would in turn evict the very ERRORs it was writing.
                 *
                 * Keep the FIRST failure loud and complete (that is the one an operator needs),
                 * then rate-limit to one line per interval with a running count, so a sustained
                 * fault stays visible without consuming the rotation budget.
                 */
                val now = System.currentTimeMillis()
                val firstFailure = failures == 1L
                if (firstFailure || now - lastQueueDrainLogAtMillis >= QUEUE_DRAIN_LOG_INTERVAL_MILLIS) {
                    lastQueueDrainLogAtMillis = now
                    Log.error(
                        "[Headless] Game-loop queue operation failed; continuing to drain the queue " +
                            "(failures=$failures, first=${firstFailure})",
                        error
                    )
                }
            }
        }
    }
}
