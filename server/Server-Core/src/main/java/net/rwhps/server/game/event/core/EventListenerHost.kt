/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.event.core


import net.rwhps.server.util.log.Log

/**
 * 服务器事件监听, 每一个实现的监听类都应该继承本方法
 *
 * @date 2023/7/5 10:23
 * @author Dr (dr@der.kim)
 */
interface EventListenerHost {
    /**
     * 捕获未处理的异常
     *
     * @param exception Throwable
     */
    fun handleException(exception: Throwable) {
        // 处理未捕获的异常
        Log.error(exception)
    }
}