/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.internal.net.rudp.impl

/**
 *  ACK Segment
 *
 *   0 1 2 3 4 5 6 7 8            15
 *  +-+-+-+-+-+-+-+-+---------------+
 *  |0|1|0|0|0|0|0|0|       6       |
 *  +-+-+-+-+-+-+-+-+---------------+
 *  | Sequence #    |   Ack Number  |
 *  +---------------+---------------+
 *  |           Checksum            |
 *  +---------------+---------------+
 *
 * @author Adrian Granados (agranados@ihmc.us)
 * @author Dr (dr@der.kim)
 */
open class ACKSegment: Segment {
    constructor()

    constructor(sequence: Int, ackNumber: Int) {
        init(ACK_FLAG.toInt(), sequence, RUDP_HEADER_LEN)
        ack = ackNumber
    }

    override fun type(): String {
        return "ACK"
    }
}
