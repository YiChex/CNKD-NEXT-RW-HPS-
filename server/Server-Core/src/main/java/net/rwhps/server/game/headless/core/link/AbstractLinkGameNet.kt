/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.headless.core.link

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.annotations.mark.GameSimulationLayer

/**
 * 用于中转服务器网络引擎部分
 *
 * ## 规则
 * 对于网络控制必须全部在 [AbstractLinkGameNet] 下进行, 不能在其他地方
 *
 * 否则可能会因为加载器问题而暴毙
 */
interface AbstractLinkGameNet {
    /**
     * 加入一个新服务器
     *
     * @param ip IP
     * @param name 需要使用的名字
     */
    @GameSimulationLayer.GameSimulationLayer_KeyWords("lastNetworkPlayerName")
    fun newConnect(
        ip: String = "127.0.0.1:${Data.config.port}", name: String = Data.headlessName
    )

    /**
     * 使用 Hess 启动一个新的服务器 （通过NIO)
     * @param port 端口
     * @param passwd 密码
     */
    @GameSimulationLayer.GameSimulationLayer_KeyWords("networking already started")
    fun startHeadlessServer(port: Int = 5123, passwd: String? = null): Boolean

    fun closeHeadlessServer(): Boolean

    fun reBootServer(run: ()->Unit = {}): Boolean
}
