/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.internal.net.rudp.impl

/*
 *  SYN Segment
 *
 *   0             7 8             15
 *  +-+-+-+-+-+-+-+-+---------------+
 *  | |A| | | | | | |               |
 *  |1|C|0|0|0|0|0|0|       22      |
 *  | |K| | | | | | |               |
 *  +-+-+-+-+-+-+-+-+---------------+
 *  +  Sequence #   +   Ack Number  |
 *  +---------------+---------------+
 *  | Vers  | Spare | Max # of Out  |
 *  |       |       | standing Segs |
 *  +---------------+---------------+
 *  | Option Flags  |     Spare     |
 *  +---------------+---------------+
 *  |     Maximum Segment Size      |
 *  +---------------+---------------+
 *  | Retransmission Timeout Value  |
 *  +---------------+---------------+
 *  | Cumulative Ack Timeout Value  |
 *  +---------------+---------------+
 *  |   Null Segment Timeout Value  |
 *  +---------------+---------------+
 *  |  Max Retrans  | Max Cum Ack   |
 *  +---------------+---------------+
 *  | Max Out of Seq| Max Auto Reset|
 *  +---------------+---------------+
 *  |           Checksum            |
 *  +---------------+---------------+
 *
 * @author Adrian Granados (agranados@ihmc.us)
 * @author Dr (dr@der.kim)
 */
class SYNSegment: Segment {
    constructor()
    constructor(
        sequence: Int,
        maxseg: Int,
        maxsegsize: Int,
        rettoval: Int,
        cumacktoval: Int,
        niltoval: Int,
        maxret: Int,
        maxcumack: Int,
        maxoutseq: Int,
        maxautorst: Int
    ) {
        init(SYN_FLAG.toInt(), sequence, SYN_HEADER_LEN)
        version = RUDP_VERSION
        maxOutstandingSegments = maxseg
        optionFlags = 0x01 /* no options */
        maxSegmentSize = maxsegsize
        retransmissionTimeout = rettoval
        cummulativeAckTimeout = cumacktoval
        nulSegmentTimeout = niltoval
        maxRetransmissions = maxret
        maxCumulativeAcks = maxcumack
        maxOutOfSequence = maxoutseq
        maxAutoReset = maxautorst
    }

    override fun type(): String {
        return "SYN"
    }

    override val bytes: ByteArray
        get() {
            val buffer = super.bytes
            buffer!![4] = (version shl 4 and 0xFF).toByte()
            buffer[5] = (maxOutstandingSegments and 0xFF).toByte()
            buffer[6] = (optionFlags and 0xFF).toByte()
            buffer[7] = 0 /* spare */
            buffer[8] = (maxSegmentSize ushr 8 and 0xFF).toByte()
            buffer[9] = (maxSegmentSize ushr 0 and 0xFF).toByte()
            buffer[10] = (retransmissionTimeout ushr 8 and 0xFF).toByte()
            buffer[11] = (retransmissionTimeout ushr 0 and 0xFF).toByte()
            buffer[12] = (cummulativeAckTimeout ushr 8 and 0xFF).toByte()
            buffer[13] = (cummulativeAckTimeout ushr 0 and 0xFF).toByte()
            buffer[14] = (nulSegmentTimeout ushr 8 and 0xFF).toByte()
            buffer[15] = (nulSegmentTimeout ushr 0 and 0xFF).toByte()
            buffer[16] = (maxRetransmissions and 0xFF).toByte()
            buffer[17] = (maxCumulativeAcks and 0xFF).toByte()
            buffer[18] = (maxOutOfSequence and 0xFF).toByte()
            buffer[19] = (maxAutoReset and 0xFF).toByte()
            return buffer
        }

    override fun parseBytes(buffer: ByteArray, off: Int, len: Int) {
        super.parseBytes(buffer, off, len)
        require(len >= SYN_HEADER_LEN) { "Invalid SYN segment" }
        version = buffer[off + 4].toInt() and 0xFF ushr 4
        require(version == RUDP_VERSION) { "Invalid Reliable UDP version" }
        maxOutstandingSegments = buffer[off + 5].toInt() and 0xFF
        optionFlags = buffer[off + 6].toInt() and 0xFF
        // spare     =  (buffer[off+ 7] & 0xFF);
        maxSegmentSize = buffer[off + 8].toInt() and 0xFF shl 8 or (buffer[off + 9].toInt() and 0xFF shl 0)
        retransmissionTimeout = buffer[off + 10].toInt() and 0xFF shl 8 or (buffer[off + 11].toInt() and 0xFF shl 0)
        cummulativeAckTimeout = buffer[off + 12].toInt() and 0xFF shl 8 or (buffer[off + 13].toInt() and 0xFF shl 0)
        nulSegmentTimeout = buffer[off + 14].toInt() and 0xFF shl 8 or (buffer[off + 15].toInt() and 0xFF shl 0)
        maxRetransmissions = buffer[off + 16].toInt() and 0xFF
        maxCumulativeAcks = buffer[off + 17].toInt() and 0xFF
        maxOutOfSequence = buffer[off + 18].toInt() and 0xFF
        maxAutoReset = buffer[off + 19].toInt() and 0xFF
    }

    var version = 0
        private set
    var maxOutstandingSegments = 0
        private set
    var optionFlags = 0
        private set
    var maxSegmentSize = 0
        private set
    var retransmissionTimeout = 0
        private set
    var cummulativeAckTimeout = 0
        private set
    var nulSegmentTimeout = 0
        private set
    var maxRetransmissions = 0
        private set
    var maxCumulativeAcks = 0
        private set
    var maxOutOfSequence = 0
        private set
    var maxAutoReset = 0
        private set

    companion object {
        private const val SYN_HEADER_LEN: Int = RUDP_HEADER_LEN + 16
    }
}
