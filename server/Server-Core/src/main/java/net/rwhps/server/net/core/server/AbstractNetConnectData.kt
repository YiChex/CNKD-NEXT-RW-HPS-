/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.core.server

/**
 * Only provide interface but not implement
 * As the interface of game CoreNet, it provides various data support for GameServer and GameRelay
 * @author Dr (dr@der.kim)
 */
interface AbstractNetConnectData {
    /** Player Name */
    val name: String

    /** Player UUID?  */
    val registerPlayerId: String?

    /** Player Is Beta Version  */
    val betaGameVersion: Boolean

    /** Player Version */
    val clientVersion: Int
}