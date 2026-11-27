/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.io.packet

import net.rwhps.server.util.inline.toStringHex

/**
 * @author Dr (dr@der.kim)
 */
class GameCommandPacket(
    val sendBy: Int, val bytes: ByteArray, val gzip: Boolean = true
) {
    /**
     * Return detailed Packet data
     * @return Packet String
     */
    override fun toString(): String {
        return """
                GameCommandPacket {
                    Bytes=${bytes.contentToString()}
                    BytesHex=${bytes.toStringHex()}
                    sendBy=${sendBy}
                }
                """.trimIndent()
    }
}