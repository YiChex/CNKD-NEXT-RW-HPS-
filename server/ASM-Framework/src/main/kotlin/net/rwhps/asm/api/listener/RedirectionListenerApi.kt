/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.api.listener

import net.rwhps.asm.manager.listener.RedirectionListenerManagerImpl

/**
 * 方法重定向-监听, 在方法前或方法后添加一个监听
 *
 * @date 2023/10/22 9:26
 * @author Dr (dr@der.kim)
 */
object RedirectionListenerApi {
    /**
     *  Not using a ServiceLoader for now, modularized environments with
     *  multiple ClassLoaders cause some issues.
     *
     * @return an implementation of the [RedirectionManager].
     */
    private val redirectionManager: RedirectionListenerManager = RedirectionListenerManagerImpl()

    /**
     *
     * @see Redirection
     */
    // used by the transformer
    @JvmStatic
    @Suppress("UNUSED")
    @Throws(Throwable::class)
    operator fun invoke(obj: Any, desc: String, vararg args: Any?) {
        redirectionManager.invoke(obj, desc, *args)
    }
}