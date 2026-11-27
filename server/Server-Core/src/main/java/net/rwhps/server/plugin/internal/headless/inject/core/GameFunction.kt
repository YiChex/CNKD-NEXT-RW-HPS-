/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.core

import com.corrodinggames.rts.game.i
import net.rwhps.server.dependent.redirections.game.clas.GameLoopOperationDrain
import net.rwhps.server.game.headless.core.AbstractGameFunction
import net.rwhps.server.util.log.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 *
 *
 * @date 2023/12/22 17:17
 * @author Dr (dr@der.kim)
 */
class GameFunction : AbstractGameFunction {
    companion object {
        private const val GAME_THREAD_OPERATION_TIMEOUT_MILLIS = 30_000L
        /**
         * 与 [GameLoopOperationDrain] 的 DEFAULT_STALE_AFTER_MILLIS 保持一致（30s）。
         * 用于对**跨加载器共享**的 tick 读数做存活判定。
         */
        private const val LOOP_STALE_AFTER_MILLIS = 30_000L
        /**
         * P0-STALL/task-38: once the game thread has entered the callback we keep waiting, but
         * never without a bound. Before this, the second wait was unbounded, so a callback that
         * never returned pinned the calling thread (the only Netty worker) forever.
         */
        private const val GAME_THREAD_OPERATION_HARD_CAP_MILLIS = 30_000L
    }

    @Volatile
    private var gameLoopThread: Thread? = null

    init {
        // The queue is drained by the original game update loop before map and render work.
        enqueueGameLoopProbe()
    }

    override fun suspendMainThreadOperations(run: Runnable): Boolean {
        if (Thread.currentThread() === gameLoopThread) {
            return runInline(run)
        }

        val health = GameLoopOperationDrain.healthSnapshot()
        /*
         * CROSS-LOADER FIX: healthSnapshot() 读的是**本副本**（游戏加载器）的字段，
         * 而唯一真正写 tick 的 `FPSSleepRedirections` 在 **App 加载器**。
         * 实测：游戏副本 loopTickCount 恒 0（两次采样 0→0），App 副本 4377→6693。
         *
         * 因此这里改用跨加载器共享的 System property 读数。若共享值可用则优先使用它，
         * 否则回退到本副本读数（保持向后兼容：单副本场景下两者等价）。
         */
        val sharedLastTick = GameLoopOperationDrain.crossLoaderLastTickAtMillis()
        val effectiveLastTick = if (sharedLastTick > 0L) sharedLastTick else health.lastTickAtMillis
        val effectiveThreadName =
            if (sharedLastTick > 0L) GameLoopOperationDrain.crossLoaderLastTickThreadName() else health.threadName
        val effectiveState = net.rwhps.server.plugin.internal.headless.support.GameLoopLivenessPolicy.evaluate(
            effectiveLastTick,
            System.currentTimeMillis(),
            LOOP_STALE_AFTER_MILLIS
        )
        if (effectiveState == net.rwhps.server.plugin.internal.headless.support.GameLoopLivenessPolicy.State.STALE) {
            Log.error(
                "[Headless] Game-thread operation rejected because the game loop is stale: " +
                    "ageMs=${net.rwhps.server.plugin.internal.headless.support.GameLoopLivenessPolicy.ageMillis(
                        effectiveLastTick, System.currentTimeMillis()
                    )} thread=${effectiveThreadName.ifBlank { "unknown" }}"
            )
            return false
        }
        /*
         * P0-STALL (NOT_OBSERVED gap): the loop has never ticked, which is *not* the same condition
         * as STALE and used to be completely unguarded.
         *
         * Two very different situations hide behind NOT_OBSERVED, and they must not be treated
         * alike (an earlier revision shortened the wait for both, which risked failing a
         * legitimately slow startup -- big map / mod-package reads happen between
         * startHeadlessServer and the first tick):
         *
         *   1. the loop is still starting up  -> keep the full window; a slow start must still succeed;
         *   2. the loop is confirmed dead     -> reject immediately, because waiting is pointless
         *      and this call is holding the only Netty worker.
         *
         * GameLoopOperationDrain decides which by comparing elapsed time since the loop became
         * expected against its grace period, so this no longer depends on a hand-tuned timeout.
         */
        /*
         * P0-STALL (NOT_OBSERVED): this copy has never recorded a tick. It must NOT be treated as
         * "dead" and rejected -- but it IS a genuine warning sign, not noise.
         *
         * `jcmd VM.class_hierarchy` on the live host proves GameLoopOperationDrain is loaded TWICE
         * (the `...redirections.game.clas` prefix goes into the game loader, while
         * FPSSleepRedirections lives in `...redirections.game` and stays app-loader). This method
         * reads the GAME-loader copy, whose ticks come from the ASM hook on `game/i.b(FI)V`.
         *
         * The .bin bake does NOT contain that hook, but the runtime agent injects it at defineClass
         * time (independently verified: baked=0 -> after-agent=2 references), so this copy DOES tick
         * on a healthy server, normally within the first frame. Therefore NOT_OBSERVED here is NOT
         * normal: it means the loop really has not run yet.
         *
         * We still do not reject, because the cost of being wrong is asymmetric: a false reject
         * breaks every game-thread operation on a working server, whereas waiting costs at most one
         * bounded timeout on a server that is already broken. So: wait, but warn clearly and point
         * at the real diagnostic.
         */
        /*
         * A2-P4 (independent verification): "alive but not progressing" is the zombie symptom
         * reached by another route.
         *
         * When frames keep throwing, the guard swallows them so the thread survives -- which means
         * recordLoopTick() (injected at the HEAD of updateAndRender) still fires, the state stays
         * HEALTHY, and none of the dead-loop machinery engages. But no frame actually completes, so
         * an operation queued now will never be drained and this call burns the full 30s timeout
         * while holding the only Netty worker. That is exactly the freeze users report.
         *
         * Fail fast instead of waiting pointlessly. `GameLoopExceptionGuard` is game-loader (same as
         * this class), so there is no cross-copy hazard: this reads the copy that actually records
         * the failures.
         */
        if (net.rwhps.server.dependent.redirections.game.clas.GameLoopExceptionGuard.isNotProgressing()) {
            Log.error(
                "[Headless] Game-thread operation rejected: the game loop is ALIVE BUT NOT " +
                    "PROGRESSING (frames keep failing, none completes). Not waiting " +
                    "${GAME_THREAD_OPERATION_TIMEOUT_MILLIS}ms because the operation would never " +
                    "run and the calling thread is the only Netty worker. " +
                    "See '[LoopGuard] GAME LOOP IS NOT PROGRESSING' in Log.txt."
            )
            return false
        }

        // 用跨加载器共享读数判定（见上方 CROSS-LOADER FIX 说明）。
        val loopNeverObserved =
            effectiveState == net.rwhps.server.plugin.internal.headless.support.GameLoopLivenessPolicy.State.NOT_OBSERVED
        if (loopNeverObserved) {
            Log.warn(
                "[Headless] Game-thread operation requested before the game loop has ticked " +
                    "(sharedTicksAvailable=${sharedLastTick > 0L}, thread=${effectiveThreadName.ifBlank { "unknown" }}); " +
                    "waiting up to ${GAME_THREAD_OPERATION_TIMEOUT_MILLIS}ms. " +
                    "On a healthy server the loop ticks within the first frame, so a persistent " +
                    "NOT_OBSERVED means the game loop is NOT running (or died before its first " +
                    "tick). Check for '[Error] Thread-' in Log.txt and see " +
                    "'[Headless] GAME LOOP IS DEAD'."
            )
        }
        val waitMillis = GAME_THREAD_OPERATION_TIMEOUT_MILLIS

        val completion = CountDownLatch(1)
        val state = AtomicReference(OperationState.QUEUED)
        val failure = AtomicReference<Throwable?>(null)
        val queuedOperation = Runnable {
            if (!state.compareAndSet(OperationState.QUEUED, OperationState.RUNNING)) {
                completion.countDown()
                return@Runnable
            }

            gameLoopThread = Thread.currentThread()
            try {
                run.run()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                state.set(OperationState.COMPLETED)
                completion.countDown()
            }
        }

        if (!enqueueGameThreadOperation(queuedOperation)) {
            return false
        }

        val completed = try {
            completion.await(waitMillis, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            if (state.compareAndSet(OperationState.QUEUED, OperationState.CANCELLED)) {
                Thread.currentThread().interrupt()
                Log.warn("[Headless] Game-thread operation wait was interrupted before execution", error)
                return false
            }

            Log.warn("[Headless] Game-thread operation wait was interrupted after execution started", error)
            awaitCompletionUninterruptibly(completion, GAME_THREAD_OPERATION_HARD_CAP_MILLIS)
            failure.get()?.let { failureError ->
                Log.error("[Headless] Game-thread operation failed", failureError)
                return false
            }
            Thread.currentThread().interrupt()
            return state.get() == OperationState.COMPLETED
        }

        if (!completed) {
            val cancelledBeforeStart = state.compareAndSet(OperationState.QUEUED, OperationState.CANCELLED)
            if (!cancelledBeforeStart) {
                // Once the game thread has entered the callback, returning false would let
                // lifecycle callers start a second reset while the first one is still running.
                Log.warn(
                    "[Headless] Game-thread operation exceeded ${GAME_THREAD_OPERATION_TIMEOUT_MILLIS}ms " +
                        "after execution started; waiting up to " +
                        "${GAME_THREAD_OPERATION_HARD_CAP_MILLIS}ms more"
                )
                val finished = awaitCompletionUninterruptibly(
                    completion,
                    GAME_THREAD_OPERATION_HARD_CAP_MILLIS
                )
                if (!finished) {
                    Log.error(
                        "[Headless] Game-thread operation is STILL running " +
                            "${GAME_THREAD_OPERATION_HARD_CAP_MILLIS}ms after the initial " +
                            "${GAME_THREAD_OPERATION_TIMEOUT_MILLIS}ms timeout (state=${state.get()}); " +
                            "giving up the wait so the calling thread is not blocked indefinitely"
                    )
                    return false
                }
                failure.get()?.let { error ->
                    Log.error("[Headless] Game-thread operation failed", error)
                    return false
                }
                return state.get() == OperationState.COMPLETED
            }
            Log.error(
                "[Headless] Game-thread operation timed out after ${waitMillis}ms " +
                    "state=${state.get()} cancelledBeforeStart=$cancelledBeforeStart " +
                    "loopNeverTicked=$loopNeverObserved ticks=${health.tickCount}"
            )
            return false
        }

        failure.get()?.let { error ->
            Log.error("[Headless] Game-thread operation failed", error)
            return false
        }
        return state.get() == OperationState.COMPLETED
    }

    /** Returns true if the latch reached zero within [timeoutMillis]. */
    private fun awaitCompletionUninterruptibly(completion: CountDownLatch, timeoutMillis: Long): Boolean {
        var interrupted = false
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) {
                break
            }
            try {
                if (completion.await(remaining, TimeUnit.MILLISECONDS)) {
                    break
                }
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
        return completion.count == 0L
    }

    private fun enqueueGameLoopProbe() {
        enqueueGameThreadOperation(Runnable {
            gameLoopThread = Thread.currentThread()
        })
    }

    private fun enqueueGameThreadOperation(operation: Runnable): Boolean {
        return runCatching {
            val engine = GameEngine.gameEngine as? i
                ?: error("Headless game loop queue is unavailable")
            val queue = engine.k
            queue.add(operation)
            /*
             * P0-STALL: hand the proven queue to the drain, so the drain no longer has to reflect
             * its way to it and cannot silently fail when `l.B()` returns an `l` rather than an `i`.
             *
             * ⚠️ Scope limit (A1-03b, independent audit): GameLoopOperationDrain is loaded TWICE --
             * once by the game loader (this class, and the ASM hook on `game/i.b(FI)V`) and once by
             * the app loader (`FPSSleepRedirections`, which calls drainCurrentEngineQueue every
             * frame). This call writes the GAME copy, so the benefit applies to the hook-driven
             * drain only; the app-loader drain still falls back to `currentEngineQueue` or
             * reflection. That is not a correctness problem -- the reflective resolve is cached into
             * `currentEngineQueue` after one success -- but the "no reflection" claim is only half
             * true and should not be stated as if it covered both copies.
             */
            GameLoopOperationDrain.registerEngineQueue(queue)
            true
        }.getOrElse { error ->
            Log.error("[Headless] Failed to queue game-thread operation", error)
            false
        }
    }

    private fun runInline(run: Runnable): Boolean {
        return runCatching {
            run.run()
            true
        }.getOrElse { error ->
            Log.error("[Headless] Game-thread operation failed", error)
            false
        }
    }

    private enum class OperationState {
        QUEUED,
        RUNNING,
        CANCELLED,
        COMPLETED
    }

    override val neverEnd: IntArray
        get() {
            val mapEngine = GameEngine.mapEngineOrNull ?: return IntArray(0)
            if (!mapEngine.W) {
                return IntArray(0)
            }
            return intArrayOf(mapEngine.C, mapEngine.D)
        }
}
