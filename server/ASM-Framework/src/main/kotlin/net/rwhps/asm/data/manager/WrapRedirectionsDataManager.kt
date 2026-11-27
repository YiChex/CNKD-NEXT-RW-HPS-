/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.data.manager

import net.rwhps.asm.api.wrap.RedirectionWrapper
import net.rwhps.asm.data.MethodTypeInfoValue

/**
 * `redirectWrap` 的登记表（FIX-LOOP-GUARD）。
 *
 * 与 [ListenerRedirectionsDataManager] 平行但**完全独立**，因为：
 * - 同一个 `(class, method, desc)` 可以**同时**注册 `redirectL`（方法首/尾监听）与
 *   `redirectWrap`（方法体包装）——两者语义不同、必须共存，不能共用一个 map 的 "Repeat" 校验；
 * - 包装器只需要「每个方法一个处理器」，不需要 before/after 两个方向。
 *
 * @author FIX-LOOP-GUARD
 */
object WrapRedirectionsDataManager {
    /** Partial Class Path 缓存，减少查询 */
    internal val partialClassPathCache = ArrayList<String>()

    /** 需要包装方法体的方法 */
    internal val partialWrapMethodName = HashMap<String, ArrayList<MethodTypeInfoValue>>()

    /** `MethodTypeInfoValue.desc` -> 处理器实现类（Kotlin `object`，含静态 `INSTANCE`） */
    internal val wrapData = HashMap<String, Class<out RedirectionWrapper>>()

    /**
     * 登记「把 [methodTypeInfoValue] 指向的方法体包进 try/catch」。
     *
     * @param methodTypeInfoValue 目标方法（`classPath` / `methodName` / `methodParamsInfo`）
     * @param wrapperClass 处理器实现类；**必须是 Kotlin `object`**（框架按
     *   `GETSTATIC <class>.INSTANCE` 取实例，与 [ListenerRedirectionsDataManager] 的约定一致）
     */
    @JvmStatic
    fun addPartialMethodWrap(
        methodTypeInfoValue: MethodTypeInfoValue,
        wrapperClass: Class<out RedirectionWrapper>
    ) {
        if (methodTypeInfoValue.listenerClass != null) {
            throw NullPointerException("It should not be passed in : ListenerClass")
        }
        if (methodTypeInfoValue.replaceClass != null) {
            throw NullPointerException("It should not be passed in : ReplaceClass")
        }

        val list = partialWrapMethodName.getOrPut(methodTypeInfoValue.classPath) { ArrayList() }
        if (list.contains(methodTypeInfoValue)) {
            throw Exception("Repeat")
        }
        list.add(methodTypeInfoValue)

        wrapData[methodTypeInfoValue.desc] = wrapperClass
        partialClassPathCache.add(methodTypeInfoValue.classPath)
    }
}