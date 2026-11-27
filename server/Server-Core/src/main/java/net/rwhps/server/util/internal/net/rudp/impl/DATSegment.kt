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
 *  Data Segment
 *
 *   0 1 2 3 4 5 6 7 8            15
 *  +-+-+-+-+-+-+-+-+---------------+
 *  |0|1|0|0|0|0|0|0|       6       |
 *  +-+-+-+-+-+-+-+-+---------------+
 *  | Sequence #    |   Ack Number  |
 *  +---------------+---------------+
 *  |           Checksum            |
 *  +---------------+---------------+
 *  | ...                           |
 *  +-------------------------------+
 *
 * @author Adrian Granados (agranados@ihmc.us)
 * @author Dr (dr@der.kim)
 */
class DATSegment: Segment {
    lateinit var data: ByteArray
        private set

    constructor()

    constructor(sequence: Int, ackn: Int, b: ByteArray?, off: Int, len: Int) {
        init(ACK_FLAG.toInt(), sequence, RUDP_HEADER_LEN)
        ack = ackn
        data = ByteArray(len)
        System.arraycopy(b, off, data, 0, len)
    }

    override fun length(): Int {
        return data.size + super.length()
    }

    override fun type(): String {
        return "DAT"
    }

    override val bytes: ByteArray?
        get() {
            val buffer = super.bytes
            System.arraycopy(data, 0, buffer, RUDP_HEADER_LEN, data.size)
            return buffer
        }

    public override fun parseBytes(buffer: ByteArray, off: Int, len: Int) {
        super.parseBytes(buffer, off, len)
        data = ByteArray(len - RUDP_HEADER_LEN)
        System.arraycopy(buffer, off + RUDP_HEADER_LEN, data, 0, data.size)
    }
}
