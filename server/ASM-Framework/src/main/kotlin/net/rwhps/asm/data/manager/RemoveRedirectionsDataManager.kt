/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.data.manager

import net.rwhps.asm.data.MethodTypeInfoValue

/**
 *
 *
 * @date 2023/10/22 9:48
 * @author Dr (dr@der.kim)
 */
object RemoveRedirectionsDataManager {
    /** Partial Class Path缓存 建少查询 */
    internal val partialClassPathCache = ArrayList<String>()
    /** 替代指定方法 */
    internal val partialListenerMethodName = HashMap<String, ArrayList<MethodTypeInfoValue>>()

    /**
     * 加入 指定 Class 的指定方法代理
     *
     *
     * @param desc String
     * @param p Array<String>
     * @param redirection Redirection
     */
    @JvmStatic
    fun addPartialMethodRemove(methodTypeInfoValue: MethodTypeInfoValue) {
        if (methodTypeInfoValue.replaceClass != null || methodTypeInfoValue.listenerClass != null) {
            throw NullPointerException("Parameter error")
        }

        if (partialListenerMethodName.containsKey(methodTypeInfoValue.classPath)) {
            val list = partialListenerMethodName[methodTypeInfoValue.classPath]!!
            if (list.contains(methodTypeInfoValue)) {
                throw Exception("Repeat")
            }
            list.add(methodTypeInfoValue)
        } else {
            partialListenerMethodName[methodTypeInfoValue.classPath] = ArrayList<MethodTypeInfoValue>().also { it.add(methodTypeInfoValue) }
        }

        addPartialClassPathCache(methodTypeInfoValue.classPath)
    }

    private fun addPartialClassPathCache(classPath: String) {
        partialClassPathCache.add(classPath)
    }
}