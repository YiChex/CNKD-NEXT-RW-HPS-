/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.data.manager

import net.rwhps.asm.api.replace.RedirectionReplace
import net.rwhps.asm.data.MethodTypeInfoValue
import net.rwhps.asm.func.Find
import net.rwhps.asm.util.fast.DefaultValueClass


/**
 *
 *
 * @date 2023/10/22 9:48
 * @author Dr (dr@der.kim)
 */
object ReplaceRedirectionsDataManager {
    /** All Class Path缓存 建少查询 */
    internal val allClassPathCache = ArrayList<String>()
    /** 全替换 自定义过滤包名 */
    internal val allReplacePacketName = ArrayList<String>()
    /** 全替换 自定义匹配规则*/
    internal val allReplaceFindPacketName = ArrayList<Find<String, Boolean>>()
    /***/
    internal val allRemoveSyncClassPathCache = ArrayList<String>()

    /** Partial Class Path缓存 建少查询 */
    internal val partialClassPathCache = ArrayList<String>()
    /** 替代指定方法 */
    internal val partialReplaceMethodName = HashMap<String, ArrayList<MethodTypeInfoValue>>()

    internal val descData = HashMap<String, RedirectionReplace>()

    @JvmStatic
    @JvmOverloads
    fun addAllMethodReplace(classPath: String, removeSync: Boolean = false) {
        allReplacePacketName.add(classPath)
        addClassPathCache(classPath)
        if (removeSync) {
            allRemoveSyncClassPathCache.add(classPath)
        }
    }

    @JvmStatic
    fun addAllMethodReplace(classFind: Find<String, Boolean>) {
        allReplaceFindPacketName.add(classFind)
    }

    /**
     * 加入 指定 Class 的指定方法代理
     *
     *
     * @param desc String
     * @param p Array<String>
     * @param redirection Redirection
     */
    @JvmStatic
    fun addPartialMethodReplace(methodTypeInfoValue: MethodTypeInfoValue, redirection: RedirectionReplace?) {
        if (methodTypeInfoValue.replaceClass == null && redirection == null) {
            throw NullPointerException("Parameter error")
        }

        if (methodTypeInfoValue.listenerClass != null) {
            throw NullPointerException("It should not be passed in : ListenerClass")
        }

        methodTypeInfoValue.listenerOrReplace = false

        if (partialReplaceMethodName.containsKey(methodTypeInfoValue.classPath)) {
            val list = partialReplaceMethodName[methodTypeInfoValue.classPath]!!
            if (list.contains(methodTypeInfoValue)) {
                throw Exception("Repeat")
            }
            list.add(methodTypeInfoValue)
        } else {
            partialReplaceMethodName[methodTypeInfoValue.classPath] = ArrayList<MethodTypeInfoValue>().also { it.add(methodTypeInfoValue) }
        }

        // 如果 ReplaceClass 为 null, 那么即证明是默认替换, 才需要加入redirection
        if (methodTypeInfoValue.replaceClass == null) {
            if (redirection != null) {
                DefaultValueClass.coverPrivateValueClass(redirection)?.let {
                    methodTypeInfoValue.replaceClass = it
                }
            }
            // 这里构造 Desc 并且 加入对应的 取代
            descData[methodTypeInfoValue.desc] = redirection!!
        }
        addPartialClassPathCache(methodTypeInfoValue.classPath)
    }

    private fun addClassPathCache(classPath: String) {
        allClassPathCache.add(classPath)
    }

    private fun addPartialClassPathCache(classPath: String) {
        partialClassPathCache.add(classPath)
    }
}