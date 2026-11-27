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
 * @author Dr (dr@der.kim)
 * @date 2023/7/17 8:42
 */
internal class Counters {
    /** Segment sequence number */
    private var sequence = 0

    /** Last in-sequence received segment */
    @get:Synchronized
    var lastInSequence = 0
        private set

    /**
     * The receiver maintains a counter of unacknowledged segments received
     * without an acknowledgment being sent to the transmitter. The maximum
     * value of this counter is configurable. If this counter's maximum is
     * exceeded, the receiver sends either a stand-alone acknowledgment, or
     * an extended acknowledgment if there are currently any out-of-sequence
     * segments. The recommended value for the cumulative acknowledge counter
     * is 3.
     */
    @get:Synchronized
    var cumulativeAckCounter = 0 /* Cumulative acknowledge counter */
        private set

    /**
     * The receiver maintains a counter of the number of segments that have
     * arrived out-of-sequence. Each time this counter exceeds its configurable
     * maximum, an extended acknowledgment segment containing the sequence
     * numbers of all current out-of-sequence segments that have been received
     * is sent to the transmitter. The counter is then reset to zero. The
     * recommended value for the out-of-sequence acknowledgments counter is 3.
     */
    @get:Synchronized
    var outOfSequenceCounter = 0 /* Out-of-sequence acknowledgments counter */
        private set

    /**
     * The transmitter maintains a counter of the number of segments that
     * have been sent without getting an acknowledgment. This is used
     * by the receiver as a mean of flow control.
     */
    @get:Synchronized
    var outstandingSegsCounter = 0 /* Outstanding segments counter */
        private set

    @Synchronized
    fun nextSequenceNumber(): Int {
        return ReliableSocket.nextSequenceNumber(sequence).also { sequence = it }
    }

    @Synchronized
    fun setSequenceNumber(n: Int): Int {
        sequence = n
        return sequence
    }

    @Synchronized
    fun setLastInSequence(n: Int): Int {
        lastInSequence = n
        return lastInSequence
    }

    @Synchronized
    fun incCumulativeAckCounter() {
        cumulativeAckCounter++
    }

    @get:Synchronized
    val andResetCumulativeAckCounter: Int
        get() {
            val tmp = cumulativeAckCounter
            cumulativeAckCounter = 0
            return tmp
        }

    @Synchronized
    fun incOutOfSequenceCounter() {
        outOfSequenceCounter++
    }

    @get:Synchronized
    val andResetOutOfSequenceCounter: Int
        get() {
            val tmp = outOfSequenceCounter
            outOfSequenceCounter = 0
            return tmp
        }

    @Synchronized
    fun incOutstandingSegsCounter() {
        outstandingSegsCounter++
    }

    @get:Synchronized
    val andResetOutstandingSegsCounter: Int
        get() {
            val tmp = outstandingSegsCounter
            outstandingSegsCounter = 0
            return tmp
        }

    @Synchronized
    fun reset() {
        outOfSequenceCounter = 0
        outstandingSegsCounter = 0
        cumulativeAckCounter = 0
    }
}
