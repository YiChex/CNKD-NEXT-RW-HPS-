/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.event.core

import net.rwhps.server.func.Control

/**
 * 全部事件的父方法, 提供一个判断接口
 *
 * @date 2023/7/16 12:56
 * @author Dr (dr@der.kim)
 */
interface AbstractEvent {
    /**
     * 判断能否把当前事件传递下一个
     * @return Boolean
     */
    fun status(): Control.EventNext {
        return Control.EventNext.CONTINUE
    }
}