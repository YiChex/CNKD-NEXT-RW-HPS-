/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.api.replace

import net.rwhps.asm.manager.replace.RedirectionIgnoreManagerImpl
import net.rwhps.asm.manager.replace.RedirectionManagerImpl


/**
 * 方法重定向-取代, 取代方法
 *
 * @date 2023/10/22 9:25
 * @author Dr (dr@der.kim)
 */
object RedirectionReplaceApi {
    /**
     *  Not using a ServiceLoader for now, modularized environments with
     *  multiple ClassLoaders cause some issues.
     *
     * @return an implementation of the [RedirectionManager].
     */
    private val redirectionManager: RedirectionReplaceManager = RedirectionManagerImpl()
    private val redirectionIgnoreManager: RedirectionReplaceManager = RedirectionIgnoreManagerImpl()

    /**
     *
     * @see Redirection
     */
    // used by the transformer
    @JvmStatic
    @Suppress("UNUSED")
    @Throws(Throwable::class)
    operator fun invoke(obj: Any, desc: String, type: Class<*>, vararg args: Any?): Any? {
        return redirectionManager.invoke(obj, desc, type, *args)
    }

    // used by the transformer
    @JvmStatic
    @Suppress("UNUSED")
    @Throws(Throwable::class)
    fun invokeIgnore(obj: Any, desc: String, type: Class<*>, vararg args: Any?): Any? {
        return redirectionIgnoreManager.invoke(obj, desc, type, *args)
    }
}