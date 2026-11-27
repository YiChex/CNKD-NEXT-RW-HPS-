/*
 * Copyright (c) 2002-2012 LWJGL Project
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'LWJGL' nor the names of
 *   its contributors may be used to endorse or promote products derived
 *   from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util

import kotlin.math.max

/**
 * A highly accurate sync method that continually adapts to the system
 * it runs on to provide reliable results.
 *
 * @date 2024/1/28 18:49
 *
 * @author Riven
 * @author kappaOne
 * @author Dr (dr@der.kim)
 */
class Sync {
    /** number of nano seconds in a second  */
    private val NANOS_IN_SECOND = 1000L * 1000L * 1000L

    /** The time to sleep/yield until the next frame  */
    private var nextFrame: Long = 0

    /** whether the initialisation code has run  */
    private var initialised = false

    /** for calculating the averages the previous sleep/yield times are stored  */
    private val sleepDurations = RunningAvg(10)
    private val yieldDurations = RunningAvg(10)


    /**
     * An accurate sync method that will attempt to run at a constant frame rate.
     * It should be called once every frame.
     *
     * @param fps - the desired frame rate, in frames per second
     */
    fun sync(fps: Int) {
        if (fps <= 0) return
        if (!initialised) initialise()

        try {
            // sleep until the average sleep time is greater than the time remaining till nextFrame
            run {
                var t0 = Time.nanos()
                var t1: Long
                while ((nextFrame - t0) > sleepDurations.avg()) {
                    Thread.sleep(1)
                    sleepDurations.add((Time.nanos().also { t1 = it }) - t0) // update average sleep time
                    t0 = t1
                }
            }


            // slowly dampen sleep average if too high to avoid yielding too much
            sleepDurations.dampenForLowResTicker()


            // yield until the average yield time is greater than the time remaining till nextFrame
            var t0 = Time.nanos()
            var t1: Long
            while ((nextFrame - t0) > yieldDurations.avg()) {
                Thread.yield()
                yieldDurations.add((Time.nanos().also { t1 = it }) - t0) // update average yield time
                t0 = t1
            }
        } catch (e: InterruptedException) {
        }


        // schedule next frame, drop frame(s) if already too late for next frame
        nextFrame = max((nextFrame + NANOS_IN_SECOND / fps).toDouble(), Time.nanos().toDouble()).toLong()
    }

    /**
     * This method will initialise the sync method by setting initial
     * values for sleepDurations/yieldDurations and nextFrame.
     *
     * If running on windows it will start the sleep timer fix.
     */
    private fun initialise() {
        initialised = true

        sleepDurations.init((1000 * 1000).toLong())
        // 函数调用导致的时间差 相减
        yieldDurations.init((-(Time.nanos() - Time.nanos()) * 1.333).toInt().toLong())

        nextFrame = Time.nanos()

        val osName = System.getProperty("os.name")

        if (osName.startsWith("Win")) {
            // On windows the sleep functions can be highly inaccurate by
            // over 10ms making in unusable. However it can be forced to
            // be a bit more accurate by running a separate sleeping daemon
            // thread.
            val timerAccuracyThread = Thread {
                try {
                    Thread.sleep(Long.MAX_VALUE)
                } catch (e: Exception) {
                }
            }

            timerAccuracyThread.name = "LWJGL Timer"
            timerAccuracyThread.isDaemon = true
            timerAccuracyThread.start()
        }
    }

    private class RunningAvg(slotCount: Int) {
        private val slots = LongArray(slotCount)
        private var offset = 0

        fun init(value: Long) {
            while (this.offset < slots.size) {
                slots[offset++] = value
            }
        }

        fun add(value: Long) {
            slots[offset++ % slots.size] = value
            this.offset %= slots.size
        }

        fun avg(): Long {
            var sum: Long = 0
            for (i in slots.indices) {
                sum += slots[i]
            }
            return sum / slots.size
        }

        fun dampenForLowResTicker() {
            if (this.avg() > DAMPEN_THRESHOLD) {
                for (i in slots.indices) {
                    slots[i] = (slots[i] * DAMPEN_FACTOR).toLong()
                }
            }
        }

        companion object {
            private const val DAMPEN_THRESHOLD = 10 * 1000L * 1000L // 10ms
            private const val DAMPEN_FACTOR = 0.9f // don't change: 0.9f is exactly right!
        }
    }
}