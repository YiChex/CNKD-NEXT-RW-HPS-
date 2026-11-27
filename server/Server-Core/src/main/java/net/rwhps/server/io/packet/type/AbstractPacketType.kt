/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.io.packet.type

/**
 * 用于表示 [net.rwhps.server.io.packet.Packet] 的头数据
 *
 * @date 2024/2/24 18:54
 * @author Dr (dr@der.kim)
 */
interface AbstractPacketType {
    /** 数据包类型 */
    val typeInt: Int
    /** 数据包类型 (转bytes) */
    val typeIntBytes: ByteArray

    /** 这个数据包的名字 */
    val nameEnum: String

    /** 通过Int获取新头部 */
    fun from(type: Int): AbstractPacketType
}