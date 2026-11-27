/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.internal.net.rudp.impl

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/**
 * Timer
 *
 * @author Dr (dr@der.kim)
 */
class Timer(private val name: String, private val task: Runnable) {
    private val lock = ReentrantLock()
    private val condition: Condition = lock.newCondition()
    private var delay: Long = 0
    private var period: Long = 0
    private var canceled: Boolean = false
    private var scheduled: Boolean = false
    private var reset: Boolean = false
    private var stopped: Boolean = false

    fun start() {
        Thread {
            while (!Thread.currentThread().isInterrupted) {
                lock.lock()
                try {
                    while (!scheduled) {
                        condition.await()
                    }
                    if (stopped) {
                        break
                    }
                    if (delay > 0) {
                        if (condition.await(delay, TimeUnit.MILLISECONDS)) {
                            if (canceled) {
                                continue
                            }
                        } else {
                            if (canceled) {
                                continue
                            }
                            task.run()
                        }
                    } else {
                        if (canceled) {
                            continue
                        }
                        task.run()
                    }
                    if (period > 0) {
                        while (true) {
                            if (reset) {
                                break
                            }
                            if (condition.await(period, TimeUnit.MILLISECONDS)) {
                                if (canceled) {
                                    break
                                }
                                if (!reset) {
                                    task.run()
                                }
                            } else {
                                if (canceled) {
                                    break
                                }
                                if (!reset) {
                                    task.run()
                                }
                            }
                        }
                    }
                } finally {
                    lock.unlock()
                }
            }
        }.start()
    }

    @Synchronized
    fun schedule(delay: Long) {
        schedule(delay, 0)
    }

    @Synchronized
    fun schedule(delay: Long, period: Long) {
        this.delay = delay
        this.period = period
        if (scheduled) {
            throw IllegalStateException("already scheduled")
        }
        scheduled = true
        lock.lock()
        try {
            condition.signalAll()
        } finally {
            lock.unlock()
        }
    }

    @Synchronized
    fun isScheduled(): Boolean {
        return scheduled
    }

    @Synchronized
    fun isIdle(): Boolean {
        return !isScheduled()
    }

    @Synchronized
    fun reset() {
        lock.lock()
        try {
            reset = true
        } finally {
            lock.unlock()
        }
    }

    @Synchronized
    fun cancel() {
        lock.lock()
        try {
            canceled = true
        } finally {
            lock.unlock()
        }
    }

    @Synchronized
    fun destroy() {
        cancel()
        stopped = true
    }
}