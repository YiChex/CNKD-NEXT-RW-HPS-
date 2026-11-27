/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.manage

import net.rwhps.server.game.headless.core.AbstractGameModule
import net.rwhps.server.struct.map.ObjectMap

/**
 * 多 Hess 管理器
 * 通过 [ClassLoader.toString] 来分别不同的实例
 *
 * @author Dr (dr@der.kim)
 */
object HeadlessModuleManage {
    // RW-HPS 默认使用
    lateinit var hps: AbstractGameModule
        private set
    lateinit var hpsLoader: String

    // 多并发使用
    val hessLoaderMap = ObjectMap<String, AbstractGameModule>()

    @JvmStatic
    fun addGameModule(loadID: String, loader: AbstractGameModule) {
        if (loadID == hpsLoader) {
            hps = loader
        }

        hessLoaderMap[loadID] = loader
    }

    fun initHPS(): Boolean {
        return this::hps.isInitialized
    }
}