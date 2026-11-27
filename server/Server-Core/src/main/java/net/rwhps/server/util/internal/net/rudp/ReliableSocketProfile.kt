/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.internal.net.rudp

/**
 * This class specifies the Reliable UDP parameters of a socket.
 *
 * @author Adrian Granados
 * @see ReliableSocket
 */
class ReliableSocketProfile @JvmOverloads constructor(
    maxSendQueueSize: Int = MAX_SEND_QUEUE_SIZE,
    maxRecvQueueSize: Int = MAX_RECV_QUEUE_SIZE,
    maxSegmentSize: Int = MAX_SEGMENT_SIZE,
    maxOutstandingSegs: Int = MAX_OUTSTANDING_SEGS,/*MAX_RETRANS*/
    maxRetrans: Int = 0,
    maxCumulativeAcks: Int = MAX_CUMULATIVE_ACKS,
    maxOutOfSequence: Int = MAX_OUT_OF_SEQUENCE,
    maxAutoReset: Int = MAX_AUTO_RESET,
    nullSegmentTimeout: Int = NULL_SEGMENT_TIMEOUT,
    retransmissionTimeout: Int = RETRANSMISSION_TIMEOUT,
    cumulativeAckTimeout: Int = CUMULATIVE_ACK_TIMEOUT
) {
    private val _maxSendQueueSize: Int
    private val _maxRecvQueueSize: Int
    private val _maxSegmentSize: Int
    private val _maxOutstandingSegs: Int
    private val _maxRetrans: Int
    private val _maxCumulativeAcks: Int
    private val _maxOutOfSequence: Int
    private val _maxAutoReset: Int
    private val _nullSegmentTimeout: Int
    private val _retransmissionTimeout: Int
    private val _cumulativeAckTimeout: Int
    /**
     * Creates an profile with the specified Reliable UDP parameter values.
     *
     * @param maxSendQueueSize      maximum send queue size (packets).
     * @param maxRecvQueueSize      maximum receive queue size (packets).
     * @param maxSegmentSize        maximum segment size (octets) (must be at least 22).
     * @param maxOutstandingSegs    maximum number of outstanding segments.
     * @param maxRetrans            maximum number of consecutive retransmissions (0 means unlimited).
     * @param maxCumulativeAcks     maximum number of unacknowledged received segments.
     * @param maxOutOfSequence      maximum number of out-of-sequence received segments.
     * @param maxAutoReset          maximum number of consecutive auto resets (not used).
     * @param nullSegmentTimeout    null segment timeout (ms).
     * @param retransmissionTimeout retransmission timeout (ms).
     * @param cumulativeAckTimeout  cumulative acknowledge timeout (ms).
     */
    /**
     * Creates a profile with the default Reliable UDP parameter values.
     *
     * Note: According to the Reliable UDP protocol's draft, the default
     * maximum number of retransmissions is 3. However, if packet
     * drops are too high, the connection may get stall unless
     * the sender continues to retransmit packets that have not been
     * unacknowledged. We will use 0 instead, which means unlimited.
     *
     */
    init {
        checkValue("maxSendQueueSize", maxSendQueueSize, 1, 255)
        checkValue("maxRecvQueueSize", maxRecvQueueSize, 1, 255)
        checkValue("maxSegmentSize", maxSegmentSize, 22, 65535)
        checkValue("maxOutstandingSegs", maxOutstandingSegs, 1, 255)
        checkValue("maxRetrans", maxRetrans, 0, 255)
        checkValue("maxCumulativeAcks", maxCumulativeAcks, 0, 255)
        checkValue("maxOutOfSequence", maxOutOfSequence, 0, 255)
        checkValue("maxAutoReset", maxAutoReset, 0, 255)
        checkValue("nullSegmentTimeout", nullSegmentTimeout, 0, 65535)
        checkValue("retransmissionTimeout", retransmissionTimeout, 100, 65535)
        checkValue("cumulativeAckTimeout", cumulativeAckTimeout, 100, 65535)
        _maxSendQueueSize = maxSendQueueSize
        _maxRecvQueueSize = maxRecvQueueSize
        _maxSegmentSize = maxSegmentSize
        _maxOutstandingSegs = maxOutstandingSegs
        _maxRetrans = maxRetrans
        _maxCumulativeAcks = maxCumulativeAcks
        _maxOutOfSequence = maxOutOfSequence
        _maxAutoReset = maxAutoReset
        _nullSegmentTimeout = nullSegmentTimeout
        _retransmissionTimeout = retransmissionTimeout
        _cumulativeAckTimeout = cumulativeAckTimeout
    }

    /**
     * Returns the maximum send queue size (packets).
     */
    fun maxSendQueueSize(): Int {
        return _maxSendQueueSize
    }

    /**
     * Returns the maximum receive queue size (packets).
     */
    fun maxRecvQueueSize(): Int {
        return _maxRecvQueueSize
    }

    /**
     * Returns the maximum segment size (octets).
     */
    fun maxSegmentSize(): Int {
        return _maxSegmentSize
    }

    /**
     * Returns the maximum number of outstanding segments.
     */
    fun maxOutstandingSegs(): Int {
        return _maxOutstandingSegs
    }

    /**
     * Returns the maximum number of consecutive retransmissions (0 means unlimited).
     */
    fun maxRetrans(): Int {
        return _maxRetrans
    }

    /**
     * Returns the maximum number of unacknowledged received segments.
     */
    fun maxCumulativeAcks(): Int {
        return _maxCumulativeAcks
    }

    /**
     * Returns the maximum number of out-of-sequence received segments.
     */
    fun maxOutOfSequence(): Int {
        return _maxOutOfSequence
    }

    /**
     * Returns the maximum number of consecutive auto resets.
     */
    fun maxAutoReset(): Int {
        return _maxAutoReset
    }

    /**
     * Returns the null segment timeout (ms).
     */
    fun nullSegmentTimeout(): Int {
        return _nullSegmentTimeout
    }

    /**
     * Returns the retransmission timeout (ms).
     */
    fun retransmissionTimeout(): Int {
        return _retransmissionTimeout
    }

    /**
     * Returns the cumulative acknowledge timeout (ms).
     */
    fun cumulativeAckTimeout(): Int {
        return _cumulativeAckTimeout
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("[")
        sb.append(_maxSendQueueSize).append(", ")
        sb.append(_maxRecvQueueSize).append(", ")
        sb.append(_maxSegmentSize).append(", ")
        sb.append(_maxOutstandingSegs).append(", ")
        sb.append(_maxRetrans).append(", ")
        sb.append(_maxCumulativeAcks).append(", ")
        sb.append(_maxOutOfSequence).append(", ")
        sb.append(_maxAutoReset).append(", ")
        sb.append(_nullSegmentTimeout).append(", ")
        sb.append(_retransmissionTimeout).append(", ")
        sb.append(_cumulativeAckTimeout)
        sb.append("]")
        return sb.toString()
    }

    private fun checkValue(param: String, value: Int, minValue: Int, maxValue: Int) {
        require(!(value < minValue || value > maxValue)) { param }
    }

    companion object {
        const val MAX_SEND_QUEUE_SIZE = 32
        const val MAX_RECV_QUEUE_SIZE = 32
        const val MAX_SEGMENT_SIZE = 128
        const val MAX_OUTSTANDING_SEGS = 3
        const val MAX_RETRANS = 3
        const val MAX_CUMULATIVE_ACKS = 3
        const val MAX_OUT_OF_SEQUENCE = 3
        const val MAX_AUTO_RESET = 3
        const val NULL_SEGMENT_TIMEOUT = 2000
        const val RETRANSMISSION_TIMEOUT = 600
        const val CUMULATIVE_ACK_TIMEOUT = 300
    }
}
