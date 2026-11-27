/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.event.global

import net.rwhps.server.game.event.core.AbstractGlobalEventImpl
import net.rwhps.server.game.headless.core.AbstractGameModule

/**
 * 服务器初始化事件
 *
 * @date 2023/7/5 10:00
 * @author Dr (dr@der.kim)
 */
class ServerHessLoadEvent(val loadID: String, val gameModule: AbstractGameModule): AbstractGlobalEventImpl