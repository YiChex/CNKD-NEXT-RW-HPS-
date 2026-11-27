/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util

import java.text.SimpleDateFormat
import java.util.*

/**
 * @author Dr (dr@der.kim)
 */
object Time {
    /** Efficiency improvement under high concurrency  */
    private val INSTANCE = CurrentTimeMillisClock()

    /**
     * Get the system time in nanoseconds
     *
     * @return The current value of the system timer in nanoseconds.
     */
    @JvmStatic
    fun nanos(): Long {
        return System.nanoTime()
    }

    /**
     * @return The difference, in milliseconds, between the current time and midnight on January 1, 1970.
     */
    @JvmStatic
    fun millis(): Long {
        return System.currentTimeMillis()
    }

    @JvmStatic
    fun second(): Long {
        return System.currentTimeMillis()/1000
    }

    /**
     * @return The difference, in milliseconds, between the current time and midnight on January 1, 1970.
     */
    @JvmStatic
    fun concurrentMillis(): Long {
        return INSTANCE.now
    }

    /**
     * @return The difference, in seconds, between the current time and midnight on January 1, 1970.
     */
    @JvmStatic
    fun concurrentSecond(): Int {
        return (INSTANCE.now / 1000).toInt()
    }

    /**
     * Gets the number of nanoseconds elapsed since the last
     *
     * @param prevTime - must be nanoseconds
     * @return - Elapsed time in nanoseconds since [prevTime]
     */
    @JvmStatic
    fun getTimeSinceNanos(prevTime: Long): Long {
        return nanos() - prevTime
    }

    /**
     * Get the number of milliseconds elapsed since the last
     *
     * @param prevTime - must be milliseconds
     * @return - Elapsed time in milliseconds since [prevTime]
     */
    @JvmStatic
    fun getTimeSinceMillis(prevTime: Long): Long {
        return millis() - prevTime
    }

    @JvmStatic
    fun getTimeFutureMillis(addTime: Long): Long {
        return millis() + addTime
    }

    @JvmStatic
    fun getTimeSinceSecond(prevTime: Int): Int {
        return concurrentSecond() - prevTime
    }

    @JvmStatic
    fun getTimeFutureSecond(addTime: Int): Int {
        return concurrentSecond() + addTime
    }

    /**
     * Get JDK current time
     */
    @JvmStatic
    val utcMillis: Long
        get() {
            // 获取JDK当前时间
            val cal = Calendar.getInstance()
            // 取得时间偏移量
            val zoneOffset = cal[Calendar.ZONE_OFFSET]
            // 取得夏令时差
            val dstOffset = cal[Calendar.DST_OFFSET]
            // 从本地时间里扣除这些差量，即可以取得UTC时间
            cal.add(Calendar.MILLISECOND, -(zoneOffset + dstOffset))
            return cal.timeInMillis
        }

    @JvmStatic
    fun getMilliFormat(fot: Int): String {
        return format(concurrentMillis(), fot)
    }

    @JvmStatic
    fun getUtcMilliFormat(fot: Int): String {
        return format(utcMillis, fot)
    }

    @JvmStatic
    fun format(gmt: Long, fot: Int): String {
        val ft = arrayOf(
                "yyyy-MM-dd",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "dd-MM-yyyy HH:mm:ss",
                "MM-dd-yyyy HH:mm:ss",
                "yyyy-MM-dd_HH-mm-ss",
                "HH:mm:ss",
        )
        return SimpleDateFormat(ft[fot]).format(Date(gmt))
    }

    private class CurrentTimeMillisClock {
        @Volatile
        var now: Long

        init {
            now = System.currentTimeMillis()
            Thread(
                {
                    while (true) {
                        now = System.currentTimeMillis()
                        try {
                            Thread.sleep(100)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@Thread
                        }
                    }
                },
                "current-time-millis"
            ).apply {
                isDaemon = true
                start()
            }
        }
    }
}
