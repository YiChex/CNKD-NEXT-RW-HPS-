
/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.concurrent.threads

import io.netty.channel.EventLoopGroup
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import net.rwhps.server.util.log.Log
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong


/**
 * @author Dr (dr@der.kim)
 */
object GetNewThreadPool {
    class ObservedThreadPoolExecutor internal constructor(
        private val poolLabel: String,
        nThreads: Int,
        keepAliveSeconds: Long,
        queueCapacity: Int,
        threadFactory: ThreadFactory
    ) : ThreadPoolExecutor(
        nThreads,
        nThreads,
        keepAliveSeconds,
        TimeUnit.SECONDS,
        if (queueCapacity == Int.MAX_VALUE) LinkedBlockingQueue<Runnable>() else LinkedBlockingQueue(queueCapacity),
        threadFactory
    ) {
        private val backpressureCount = AtomicLong()

        @Volatile
        private var lastBackpressureAtMillis = -1L

        internal fun recordBackpressure() {
            val count = backpressureCount.incrementAndGet()
            lastBackpressureAtMillis = System.currentTimeMillis()

            if (count == 1L || count % 25L == 0L) {
                Log.warn(
                    "[ThreadPool] Queue is full for $poolLabel. Applying caller-runs backpressure. " +
                        "count=$count, active=$activeCount, queued=${queue.size}"
                )
            }
        }

        internal fun buildBackpressureDiagnostics(): String {
            val lastBackpressure = if (lastBackpressureAtMillis <= 0L) {
                "never"
            } else {
                "${System.currentTimeMillis() - lastBackpressureAtMillis}msAgo"
            }

            return "backpressureCount=${backpressureCount.get()}, lastBackpressure=$lastBackpressure"
        }
    }

    fun getNewFixedThreadPool(
        nThreads: Int,
        name: String,
        queueCapacity: Int = Int.MAX_VALUE,
        keepAliveSeconds: Long = 30L,
        allowCoreThreadTimeout: Boolean = false
    ): ThreadPoolExecutor {
        val executor = ObservedThreadPoolExecutor(
            name,
            nThreads,
            keepAliveSeconds,
            queueCapacity,
            ThreadFactoryName.nameThreadFactory(name)
        )
        executor.rejectedExecutionHandler = RejectedExecutionHandler { runnable, pool ->
            (pool as? ObservedThreadPoolExecutor)?.recordBackpressure()
                ?: Log.warn("[ThreadPool] Queue is full for $name. Applying caller-runs backpressure.")
            if (!pool.isShutdown) {
                runnable.run()
            }
        }
        executor.allowCoreThreadTimeOut(allowCoreThreadTimeout)
        return executor
    }

    fun getNewSingleThreadExecutor(name: String): ExecutorService {
        return Executors.newSingleThreadExecutor(ThreadFactoryName.nameThreadFactory(name))
    }

    fun getNewScheduledThreadPool(corePoolSize: Int, name: String): ScheduledExecutorService {
        return ScheduledThreadPoolExecutor(corePoolSize, ThreadFactoryName.nameThreadFactory(name))
    }

    fun getEventLoopGroup(size: Int = 0): EventLoopGroup {
        return if (Epoll.isAvailable()) {
            EpollEventLoopGroup(size)
        } else {
            NioEventLoopGroup(size)
        }
    }
}
