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
 *  EACK Segment
 *
 *   0 1 2 3 4 5 6 7 8            15
 *  +-+-+-+-+-+-+-+-+---------------+
 *  |0|1|1|0|0|0|0|0|     N + 6     |
 *  +-+-+-+-+-+-+-+-+---------------+
 *  | Sequence #    |   Ack Number  |
 *  +---------------+---------------+
 *  |1st out of seq |2nd out of seq |
 *  |  ack number   |   ack number  |
 *  +---------------+---------------+
 *  |  . . .        |Nth out of seq |
 *  |               |   ack number  |
 *  +---------------+---------------+
 *  |            Checksum           |
 *  +---------------+---------------+
 *
 * @author Adrian Granados (agranados@ihmc.us)
 * @author Dr (dr@der.kim)
 */
class EAKSegment: ACKSegment {
    lateinit var aCKs: IntArray
        private set

    constructor() {
    }

    constructor(sequence: Int, ackn: Int, acks: IntArray) {
        init(EAK_FLAG.toInt(), sequence, RUDP_HEADER_LEN + acks.size)
        ack = ackn
        aCKs = acks
    }

    override fun type(): String {
        return "EAK"
    }

    override val bytes: ByteArray?
        get() {
            val buffer = super.bytes
            for (i in aCKs.indices) {
                buffer!![4 + i] = (aCKs[i] and 0xFF).toByte()
            }
            return buffer
        }

    override fun parseBytes(buffer: ByteArray, off: Int, len: Int) {
        super.parseBytes(buffer, off, len)
        aCKs = IntArray(len - RUDP_HEADER_LEN)
        for (i in aCKs.indices) {
            aCKs[i] = buffer[off + 4 + i].toInt() and 0xFF
        }
    }


}
