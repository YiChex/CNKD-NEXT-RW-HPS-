/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.dependent.redirections.game.clas

import net.rwhps.asm.api.listener.RedirectionListener
import net.rwhps.server.util.log.Log
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicLong

/**
 * BL-NULL：在 `game.i.f()V` **入口**先把渲染闸门 `bG` 关掉，消除"`bL` 已 null、`bG` 未关"的窗口。
 *
 * ## 根因（独立验证者 task-45 字节码级确认）
 *
 * `game.i.f()V` 全文只有 20 条指令：
 * ```
 * idx 2: INVOKEVIRTUAL game/i.b(Z)V     <-- 内部持锁 aj，把 bL 置 null，然后【释放 aj】
 * idx 5: PUTFIELD     game/i.bG = false <-- 关闸门，但【不持任何锁】
 * ```
 * 两指令之间 **`aj` 已释放而 `bG` 仍为 `true`**。渲染线程在此窗口抢到 `aj`（帧入口 `a(FI)V`）
 * 就会：读 `bG → true`（过闸）→ 渲染 → 在 `b(F)V` 无保护解引用 `bL` → **NPE**。
 * `bL` 非 volatile、`bG` 是 volatile ⇒ **两者不是同一个原子状态**。
 * 窗口只有两条指令宽 ⇒ 解释了线上"罕见但可复现"（b013 启动后 ~33-36 秒才炸，每进程 0~2 次）。
 *
 * ## 为什么"在入口先关闸门"是安全的（比改顺序更保守）
 *
 * `f()` 在 idx 5 **无条件**把 `bG` 设为 `false`，因此**提前到入口关闭不改变最终状态**，
 * 只是把"关闸门"这件事**提前到清句柄之前** —— 正是闸门应有的语义（先别渲染，再拆引擎）。
 *
 * 与验证者方案 C（交换 `f()` 内两条语句）相比，本实现：
 * - **不需要新增 ASM 能力**，复用已被 task-44 对抗性验证过的 `redirectL`（方法入口插桩）；
 * - **不重写引擎方法体**，只加一次入口调用；
 * - 顺带覆盖 §1.1 的 **OOM 确定性路径**：`uncaughtException` 清 `bL` 却不碰 `bG`，
 *   但那条路径最终也经 `e() → f()` ⇒ 入口关闸门同样生效。
 *
 * ⚠️ **仍然不是 100% 根治**：本类只关闸门，`bL` 的 326 个读取点中若有**不经过 `bG` 闸门**的路径，
 * 仍可能 NPE。真正治本是修 `f()` 内语句顺序（含另外 2 处同类倒置）。
 * 本类是"零新能力、零方法体重写"的最小可靠缓解。
 */
object RenderGateGuard : RedirectionListener {

    private val gateCloseCount = AtomicLong()
    private val gateCloseFailureCount = AtomicLong()

    /**
     * The resolved gate field, published once.
     *
     * A single volatile holder rather than a separate "resolved" flag: success is the only thing
     * worth caching. Failures are NOT cached, because a permanently-cached failure would disable
     * the guard for the rest of the process -- and this guard exists precisely to make a rare race
     * impossible. Retrying on failure is cheap (this runs on the retraction path, not per frame)
     * and the log is rate-limited, so a genuinely absent field cannot spam.
     */
    @Volatile
    private var gateFieldOrNull: Field? = null
    @Volatile
    private var lastFailureLogAtMillis = 0L

    /** `gameFramework/l.bG`：`public volatile boolean`，渲染闸门。 */
    private const val GATE_FIELD_NAME = "bG"
    private const val GATE_OWNER_CLASS = "com.corrodinggames.rts.gameFramework.l"
    private const val FAILURE_LOG_INTERVAL_MILLIS = 60_000L

    override fun invoke(obj: Any, desc: String, vararg args: Any?) {
        /*
         * 闸门字段解析一次并缓存。用 getField 而非 getDeclaredField：bG 声明在父类
         * gameFramework/l 上（已 javap 确认 `public volatile boolean bG`）。
         */
        val field = resolveGateField(obj) ?: return
        try {
            field.setBoolean(obj, false)
            gateCloseCount.incrementAndGet()
        } catch (error: Throwable) {
            // 绝不抛出：本方法插在回收路径入口，抛出去会让回收流程异常终止。
            recordFailure(error)
        }
    }

    private fun resolveGateField(obj: Any): Field? {
        gateFieldOrNull?.let { return it }
        return try {
            val resolved = obj.javaClass.getField(GATE_FIELD_NAME)
            resolved.isAccessible = true
            gateFieldOrNull = resolved
            resolved
        } catch (error: Throwable) {
            recordFailure(error)
            null
        }
    }

    private fun recordFailure(error: Throwable) {
        val total = gateCloseFailureCount.incrementAndGet()
        val now = System.currentTimeMillis()
        if (now - lastFailureLogAtMillis < FAILURE_LOG_INTERVAL_MILLIS) {
            return
        }
        lastFailureLogAtMillis = now
        try {
            Log.error(
                "[RenderGateGuard] 无法在 game.i.f() 入口关闭渲染闸门 $GATE_OWNER_CLASS#$GATE_FIELD_NAME" +
                    "（第 $total 次）；BL-NULL 竞态窗口仍然存在。" +
                    "注意：闸门未关 ⇒ bL 置 null 与 bG 关闭之间的窗口仍开放，" +
                    "此时由 redirectWrap 的 catch-all 兜底（单帧异常不再杀主循环）。",
                error
            )
        } catch (_: Throwable) {
            // 日志失败也不能抛出。
        }
    }

    @JvmStatic
    fun gateCloseCount(): Long = gateCloseCount.get()

    @JvmStatic
    fun gateCloseFailureCount(): Long = gateCloseFailureCount.get()

    @JvmStatic
    fun resetForTest() {
        gateFieldOrNull = null
        lastFailureLogAtMillis = 0L
        gateCloseCount.set(0L)
        gateCloseFailureCount.set(0L)
    }
}
