/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.api.wrap

/**
 * 方法体异常包装处理器（FIX-LOOP-GUARD）。
 *
 * 与 [net.rwhps.asm.api.listener.RedirectionListener] 的区别：
 * - `RedirectionListener` 只在方法**首/尾**插入一次调用，无法捕获方法体抛出的异常；
 * - `RedirectionWrapper` 由 `redirectWrap` 注册，框架会把**整个原方法体**包进
 *   `try { <原指令> } catch (Throwable t) { <本接口 invoke> ; <默认返回> }`，
 *   因此方法体抛出的任何异常都会先到这里。
 *
 * **契约**：
 * 1. 实现**必须自行重抛** `VirtualMachineError` / `ThreadDeath`（JVM 致命错误不得吞掉）。
 *    框架在调用本接口**之前**已经重抛了这两类，这里的约定是**双保险**，避免实现内部再次捕获后吞掉。
 * 2. 实现应当**不抛出**，以便被包装的方法能正常返回默认值、让调用方（如 Slick 的
 *    `AppGameContainer.gameLoop`）继续下一帧。若实现抛出，异常将传播给调用方 —— 这正是
 *    被包装方法原本的行为，框架不做额外兜底（避免无限嵌套 try/catch）。
 *
 * @author FIX-LOOP-GUARD
 */
fun interface RedirectionWrapper {
    /**
     * @param obj 被包装方法的接收者；**静态方法为 `null`**
     * @param desc 被包装方法的完整描述串，格式 `L<owner>;<name><params>`（与
     *   [net.rwhps.asm.data.MethodTypeInfoValue.desc] 一致）
     * @param throwable 方法体抛出的异常（保证非 null；`VirtualMachineError`/`ThreadDeath` 已被框架重抛）
     */
    @Throws(Throwable::class)
    operator fun invoke(obj: Any?, desc: String, throwable: Throwable)

    companion object {
        const val METHOD_NAME = "invoke"

        /** `(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Throwable;)V` */
        const val METHOD_DESC = "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Throwable;)V"
    }
}
