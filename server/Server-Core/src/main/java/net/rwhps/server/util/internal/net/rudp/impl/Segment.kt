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
 * Segment
 *
 * @author Adrian Granados (agranados@ihmc.us)
 * @author Dr (dr@der.kim)
 */
abstract class Segment protected constructor() {

    abstract fun type(): String

    fun flags(): Int {
        return _flags
    }

    fun seq(): Int {
        return sequence
    }

    open fun length(): Int {
        return _hlen
    }

    var ack: Int
        get() = if (_flags and ACK_FLAG.toInt() == ACK_FLAG.toInt()) {
            _ackn
        } else -1
        set(ackn) {
            _flags = _flags or ACK_FLAG.toInt()
            _ackn = ackn
        }

    open val bytes: ByteArray?
        get() {
            val buffer = ByteArray(length())
            buffer[0] = (_flags and 0xFF).toByte()
            buffer[1] = (_hlen and 0xFF).toByte()
            buffer[2] = (sequence and 0xFF).toByte()
            buffer[3] = (_ackn and 0xFF).toByte()
            return buffer
        }

    override fun toString(): String {
        return "${type()} [ SEQ = ${seq()}, ACK = ${if (ack >= 0) "" + ack else "N/A"}, LEN = ${length()} ]"
    }

    /**
     *  RUDP Header
     *
     *   0 1 2 3 4 5 6 7 8            15
     *  +-+-+-+-+-+-+-+-+---------------+
     *  |S|A|E|R|N|C| | |    Header     |
     *  |Y|C|A|S|U|H|0|0|    Length     |
     *  |N|K|K|T|L|K| | |               |
     *  +-+-+-+-+-+-+-+-+---------------+
     *  |  Sequence #   +   Ack Number  |
     *  +---------------+---------------+
     *  |            Checksum           |
     *  +---------------+---------------+
     *
     */
    protected fun init(flags: Int, sequence: Int, len: Int) {
        _flags = flags
        this.sequence = sequence
        _hlen = len
    }

    protected open fun parseBytes(buffer: ByteArray, off: Int, len: Int) {
        _flags = buffer[off].toInt() and 0xFF
        _hlen = buffer[off + 1].toInt() and 0xFF
        sequence = buffer[off + 2].toInt() and 0xFF
        _ackn = buffer[off + 3].toInt() and 0xFF
    }

    /** Control flags field */
    private var _flags = 0

    /** Header length field */
    private var _hlen = 0

    /** Sequence number field */
    private var sequence = 0

    /** Acknowledgment number field */
    private var _ackn: Int = -1

    /** Retransmission counter */
    var retxCounter = 0

    companion object {

        const val RUDP_VERSION = 1
        const val RUDP_HEADER_LEN = 6
        const val SYN_FLAG = 0x80.toByte()
        const val ACK_FLAG = 0x40.toByte()
        const val EAK_FLAG = 0x20.toByte()
        const val RST_FLAG = 0x10.toByte()
        const val NUL_FLAG = 0x08.toByte()
        const val CHK_FLAG = 0x04.toByte()
        const val FIN_FLAG = 0x02.toByte()
        fun parse(bytes: ByteArray): Segment {
            return parse(bytes, 0, bytes.size)
        }

        @JvmStatic
        fun parse(bytes: ByteArray, off: Int, len: Int): Segment {
            var segment: Segment? = null
            require(len >= RUDP_HEADER_LEN) { "Invalid segment" }
            val flags = bytes[off].toInt()
            if (flags and SYN_FLAG.toInt() != 0) {
                segment = SYNSegment()
            } else if (flags and NUL_FLAG.toInt() != 0) {
                segment = NULSegment()
            } else if (flags and EAK_FLAG.toInt() != 0) {
                segment = EAKSegment()
            } else if (flags and RST_FLAG.toInt() != 0) {
                segment = RSTSegment()
            } else if (flags and FIN_FLAG.toInt() != 0) {
                segment = FINSegment()
            } else if (flags and ACK_FLAG.toInt() != 0) { /* always process ACKs or Data segments last */
                segment = if (len == RUDP_HEADER_LEN) {
                    ACKSegment()
                } else {
                    DATSegment()
                }
            }
            requireNotNull(segment) { "Invalid segment" }
            segment.parseBytes(bytes, off, len)
            return segment
        }
    }
}
