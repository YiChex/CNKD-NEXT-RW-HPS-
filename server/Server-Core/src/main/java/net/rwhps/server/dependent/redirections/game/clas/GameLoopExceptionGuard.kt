/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.dependent.redirections.game.clas

import net.rwhps.asm.api.wrap.RedirectionWrapper
import net.rwhps.server.util.log.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * FIX-LOOP-GUARD: 主循环回调的最后一层防线。
 *
 * 线上事故（实例 b013）的根因是：`updateAndRender` 内部的
 * `NullPointerException: this.bL is null` 逃出 `AppGameContainer.gameLoop()` 的循环，
 * 再逃出 `java.j.run()`，**把游戏主循环线程彻底杀死**，且 `Main.r` 没有任何重启路径
 * ⇒ 服务器继续接受连接，但所有主线程操作都超时（僵尸实例，持续约 5 天 19 小时）。
 *
 * 本类由 [net.rwhps.asm.transformer.WrapMethodBodyTransformer] 注入到
 * `com/corrodinggames/rts/java/b.updateAndRender(I)V` 的 catch 分支中，
 * 使**任何单帧异常只丢一帧**，而不是终结整个主循环。
 *
 * ⚠️ 这治的是"线程不死"，**不治** `bL` 生命周期这个上游根因。
 *
 * ⚠️ **覆盖面有限，不要误读为"主循环已全面防护"**：目前只包装了
 * `com/corrodinggames/rts/java/b.updateAndRender(I)V` **一个方法**。
 * `javap` 显示 `java/b.gameLoop()` 自身的异常表**只覆盖 `Thread.sleep`**，
 * 其后的 `updateFPS` / `Display.update` / `Display.processMessages` / `c.start()` 等
 * **都在表外** —— 它们抛出的异常仍会杀死主循环线程。
 * 独立验证者（task-44）另实测确认：持续异常时**线程存活但帧永不完成**
 * （600 帧：`reached=600, completed=0`），即"活着但无进展"，与 b013 症状同源、
 * 只是换了路径到达。⇒ 本守卫是**降级**而非**根治**，且**无升级机制**（仅限频日志）。
 *
 * 包路径必须在 `...redirections.game.clas`：只有该前缀（连同
 * `headless.inject` / `com.corrodinggames.rts`）会被注入 game 类加载器；
 * `...redirections.game`（无 `.clas`）是 app 加载器专属，放错会导致被包装方法
 * 解析到另一份类实例，重演双加载器状态分裂。
 */
object GameLoopExceptionGuard : RedirectionWrapper {

    private val swallowedCount = AtomicLong(0)

    @Volatile
    private var lastFullLogAtMillis = 0L
    @Volatile
    private var firstStackLogged = false
    /**
     * P2-1 (found by independent verification of 7526307): the guard stops the loop thread from
     * dying, but a body that throws on EVERY frame is then abandoned mid-way, so the frame never
     * completes. `recordLoopTick()` runs at the HEAD of updateAndRender (injected by redirectL),
     * so it still increments and the loop keeps looking HEALTHY -- "alive but making no progress",
     * the b013 symptom reached by another route.
     *
     * These two fields detect exactly that: a continuous streak of failures.
     */
    @Volatile
    private var streakStartedAtMillis = 0L
    @Volatile
    private var lastFailureAtMillis = 0L
    @Volatile
    private var lastStallReportAtMillis = 0L

    private const val FULL_STACK_LOG_INTERVAL_MILLIS = 60_000L
    /** A gap longer than this ends the current failure streak (the loop made progress). */
    private const val STREAK_RESET_GAP_MILLIS = 10_000L
    /** A streak lasting this long means the loop is effectively not progressing. */
    private const val NO_PROGRESS_REPORT_AFTER_MILLIS = 30_000L
    /** Re-report a continuing stall at most this often. */
    private const val NO_PROGRESS_REPORT_INTERVAL_MILLIS = 60_000L

    override fun invoke(obj: Any?, desc: String, throwable: Throwable) {
        // 双保险：JVM 致命错误绝不吞掉（与 Application.kt:136 / Threads.kt:127 / PluginsLoad.kt:326 一致）。
        // 正常情况下字节码里的 INSTANCEOF 分支已经重抛，这里只是防止调用方绕过。
        if (throwable is VirtualMachineError || throwable is ThreadDeath) {
            throw throwable
        }

        val total = swallowedCount.incrementAndGet()
        val now = System.currentTimeMillis()
        val streakMillis = updateFailureStreak(now)

        /*
         * Rate-limit the MESSAGE, not just the stack.
         *
         * The game loop runs at up to 120 FPS, so a persistent per-frame failure would otherwise
         * emit 120 log lines per second -- and since LogCore now persists WARN and above to disk,
         * that would flood Log.txt and rotate away genuine ERRORs. Only the first occurrence and
         * then at most one line per interval are written; the running total is always included, so
         * the true frequency stays visible.
         */
        val shouldLog = !firstStackLogged || (now - lastFullLogAtMillis) >= FULL_STACK_LOG_INTERVAL_MILLIS
        if (shouldLog) {
            firstStackLogged = true
            lastFullLogAtMillis = now
        }
        logSafely(shouldLog, total, desc, throwable)

        /*
         * Escalation: a long uninterrupted failure streak is NOT the same as a single bad frame.
         * Nothing else can see it -- the loop still ticks, so GameLoopLivenessPolicy reports
         * HEALTHY and the dead-loop detector in GameLoopOperationDrain stays quiet. Report it
         * loudly and distinctly so an operator can tell "one bad frame" from "this instance is
         * up but never finishing a frame".
         *
         * Deliberately does not auto-exit or auto-restart: mcsmanager runs these instances with
         * autoRestart=false, so exiting would turn a degraded instance into a permanently dead one.
         */
        if (shouldReportNoProgress(streakMillis, now)) {
            lastStallReportAtMillis = now
            val stallMessage =
                "[LoopGuard] GAME LOOP IS NOT PROGRESSING: ${total} frames have failed over " +
                    "${streakMillis}ms; the loop thread is alive but no frame is completing. " +
                    "This is not a single bad frame -- the instance accepts connections but will " +
                    "not advance. Last failure in $desc"
            try {
                Log.error(stallMessage, throwable)
            } catch (logFailure: Throwable) {
                try {
                    System.err.println(stallMessage + " (logging also failed: $logFailure)")
                } catch (_: Throwable) {
                    // 彻底放弃输出，但绝不抛出。
                }
            }
        }
    }

    /**
     * Records a failure at [nowMillis] and returns how long the current uninterrupted failure
     * streak has lasted. A gap longer than [STREAK_RESET_GAP_MILLIS] means at least one frame
     * completed in between, so the streak restarts.
     */
    private fun updateFailureStreak(nowMillis: Long): Long {
        val previousFailureAt = lastFailureAtMillis
        if (previousFailureAt <= 0L || nowMillis - previousFailureAt > STREAK_RESET_GAP_MILLIS) {
            streakStartedAtMillis = nowMillis
        }
        lastFailureAtMillis = nowMillis
        return nowMillis - streakStartedAtMillis
    }

    /**
     * 记录日志且**保证自身绝不抛出** —— 本类是主循环的最后一层防线，
     * 若日志本身失败并抛出去，异常会从 handler 逃出 `updateAndRender`，主循环照样会死。
     *
     * 这不是过度设计：实测中 `Log.error` 曾抛
     * `NoClassDefFoundError: Could not initialize class net.rwhps.server.data.global.Data`，
     * 正是这里的兜底接住了它。
     */
    private fun logSafely(shouldLog: Boolean, total: Long, desc: String, throwable: Throwable) {
        if (!shouldLog) {
            return
        }
        try {
            Log.error(
                "[LoopGuard] 主循环回调抛出异常，已吞掉并继续下一帧（第 $total 次）：$desc",
                throwable
            )
        } catch (logFailure: Throwable) {
            try {
                System.err.println(
                    "[LoopGuard] swallowed game-loop exception #$total in $desc: " +
                        "$throwable (logging also failed: $logFailure)"
                )
            } catch (_: Throwable) {
                // 彻底放弃输出，但绝不抛出。
            }
        }
    }

    /**
     * 已吞掉的异常总数。
     *
     * ⚠️ **不能直接静态读取**：本类位于 `...redirections.game.clas`，该前缀被
     * `GameStartInit.kt:210-223` 注入 **game 类加载器**，因此 JVM 中存在**两份**
     * `GameLoopExceptionGuard`（app 加载器一份、game 加载器一份）。被包装的
     * `java/b.updateAndRender` 调用的是 **game 加载器**那一份，而
     * `CoreCommands` 等 app 侧代码直接引用本类，读到的是**另一份**，其计数恒为 0 ——
     * 会给出误导性诊断（与 `GameLoopOperationDrain` 的既有双加载器分裂同源，见
     * `_audit2/stall/01-ROOTCAUSE.md`）。我曾把 `loopGuard.swallowedExceptions` 接进
     * `CoreCommands` 诊断，正是因为这一点而**回退**。
     *
     * ⚠️ **但"无法观测"只对"直接静态读取"成立，不要过度推广**：独立验证者（task-44）
     * 实测确认，**父加载器类型声明的接口引用可以读到子加载器侧的静态字段**（4/4 通过）——
     * 这正是仓库既有的 `AbstractGameFunction` / `GameFunction` 跨加载器模式。
     * 若将来需要结构化观测，应走**该模式**（在 app 侧声明接口、由 game 侧实现并注册），
     * 而不是直接引用本类。
     *
     * 当前生产环境的观测通道是**限频日志本身**：行内的 `第 N 次` 即真实累计次数
     * （首次立即打印，之后每 60s 一条）。本访问器目前仅供**单测/探针**使用。
     */
    @JvmStatic
    fun swallowed(): Long = swallowedCount.get()

    /** 仅供单测/探针重置状态。 */
    @JvmStatic
    fun resetForTest() {
        swallowedCount.set(0)
        lastFullLogAtMillis = 0L
        firstStackLogged = false
        streakStartedAtMillis = 0L
        lastFailureAtMillis = 0L
        lastStallReportAtMillis = 0L
    }

    /**
     * 当前连续失败已持续的毫秒数；`0` 表示当前没有失败 streak。
     * 仅供单测/探针（跨加载器读取的限制同 [swallowed]）。
     */
    @JvmStatic
    fun currentFailureStreakMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        val last = lastFailureAtMillis
        if (last <= 0L || nowMillis - last > STREAK_RESET_GAP_MILLIS) {
            return 0L
        }
        return nowMillis - streakStartedAtMillis
    }

    /**
     * 以**合成时间**记录一次失败，仅供单测/探针使用 —— 真实 streak 需要 30 秒才能观察，
     * 单测不能等那么久。生产路径永远走 [invoke] 里的 `System.currentTimeMillis()`。
     */
    @JvmStatic
    fun recordFailureForTest(nowMillis: Long): Long = updateFailureStreak(nowMillis)

    /** 是否应该发出"无进展"升级告警（纯判定，便于单测；生产由 [invoke] 调用）。 */
    @JvmStatic
    fun shouldReportNoProgress(streakMillis: Long, nowMillis: Long): Boolean {
        return streakMillis >= NO_PROGRESS_REPORT_AFTER_MILLIS &&
            nowMillis - lastStallReportAtMillis >= NO_PROGRESS_REPORT_INTERVAL_MILLIS
    }

    /**
     * A2-P4: is the loop "alive but not progressing"?
     *
     * This is the state the no-progress alert reports: the thread survives (so
     * GameLoopLivenessPolicy still says HEALTHY and the dead-loop detector stays quiet) but frames
     * keep failing, so nothing actually advances. Operations submitted in this state still burn the
     * full 30s timeout, which is the zombie symptom by another route.
     *
     * Same classloader as `GameFunction` (both live under the game-loader prefixes), so unlike
     * GameLoopOperationDrain there is no cross-copy hazard in reading it from there.
     */
    @JvmStatic
    fun isNotProgressing(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return currentFailureStreakMillis(nowMillis) >= NO_PROGRESS_REPORT_AFTER_MILLIS
    }
}
