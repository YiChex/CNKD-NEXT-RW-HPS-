/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.data.manager

import net.rwhps.asm.api.listener.RedirectionListener
import net.rwhps.asm.data.MethodTypeInfoValue

/**
 *
 *
 * @date 2023/10/22 9:48
 * @author Dr (dr@der.kim)
 */
object ListenerRedirectionsDataManager {
    /** Partial Class Path缓存 建少查询 */
    internal val partialClassPathCache = ArrayList<String>()
    /** 替代指定方法 */
    internal val partialListenerMethodName = HashMap<String, ArrayList<MethodTypeInfoValue>>()

    internal val descData = HashMap<String, RedirectionListener>()

    /**
     * 加入 指定 Class 的指定方法代理
     *
     *
     * @param methodTypeInfoValue 方法数据
     * @param redirection Redirection
     */
    @JvmStatic
    fun addPartialMethodListener(methodTypeInfoValue: MethodTypeInfoValue, redirection: RedirectionListener?) {
        if (methodTypeInfoValue.listenerClass == null && redirection == null) {
            throw NullPointerException("Parameter error")
        }

        if (methodTypeInfoValue.replaceClass != null) {
            throw NullPointerException("It should not be passed in : ReplaceClass")
        }

        methodTypeInfoValue.listenerOrReplace = true

        if (partialListenerMethodName.containsKey(methodTypeInfoValue.classPath)) {
            val list = partialListenerMethodName[methodTypeInfoValue.classPath]!!
            if (list.contains(methodTypeInfoValue)) {
                throw Exception("Repeat")
            }
            list.add(methodTypeInfoValue)
        } else {
            partialListenerMethodName[methodTypeInfoValue.classPath] = ArrayList<MethodTypeInfoValue>().also { it.add(methodTypeInfoValue) }
        }

        // 如果 ReplaceClass 为 null, 那么即证明是默认替换, 才需要加入redirection
        if (methodTypeInfoValue.listenerClass == null) {
            // 这里构造 Desc 并且 加入对应的 取代
            val desc = methodTypeInfoValue.desc + if (methodTypeInfoValue.listenerBefore) "LS" else "LE"
            descData[desc] = redirection!!
        }
        addPartialClassPathCache(methodTypeInfoValue.classPath)
    }

    private fun addPartialClassPathCache(classPath: String) {
        partialClassPathCache.add(classPath)
    }
}