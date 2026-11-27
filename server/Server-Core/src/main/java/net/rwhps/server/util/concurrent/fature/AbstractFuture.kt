/*
 *
 *  * Copyright 2020-2024 RW-HPS Team and contributors.
 *  *
 *  * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 *  * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *  *
 *  * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 *  
 */

package net.rwhps.server.util.concurrent.fature

import kotlinx.coroutines.*
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Future 接口, 适配协程
 *
 * @param V 返回的类型
 *
 * @date 2023/9/2 17:18
 * @author Dr (dr@der.kim)
 */
abstract class AbstractFuture<V>(
    protected val future: Deferred<V>
): Future<V> {
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        future.cancel(CancellationException("Cancel"))
        return true
    }

    override fun isCancelled(): Boolean {
        return future.isCancelled
    }

    override fun isDone(): Boolean {
        return future.isCompleted
    }

    /**
     * Returns the cause of the failed I/O operation if the I/O operation has
     * failed.
     *
     * @return the cause of the failure.
     * `null` if succeeded or this future is not
     * completed yet.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cause(): Throwable? {
        return if (future.isCompleted) {
            future.getCompleted() as Throwable?
        } else {
            null
        }
    }

    /**
     * Waits for this future until it is done, and rethrows the cause of the failure if this future
     * failed.
     */
    @Throws(ExecutionException::class)
    fun sync() {
        await()
        if (cause() != null) {
            throw ExecutionException(cause()!!)
        }
    }

    /**
     * Waits for this future to be completed.
     *
     * @throws InterruptedException
     * if the current thread was interrupted
     */
    @Throws(InterruptedException::class)
    fun await() {
        runBlocking {
            future.await()
        }
    }

    /**
     * Waits for this future to be completed without
     * interruption.  This method catches an [InterruptedException] and
     * discards it silently.
     */
    fun awaitUninterruptible() {
        runBlocking {
            try {
                future.await()
            } catch (_: InterruptedException) {
                // No use
            } catch (_: CancellationException) {
                // No use
            }
        }
    }

    /**
     * Waits for this future to be completed within the
     * specified time limit.
     *
     * @return `true` if and only if the future was completed within
     * the specified time limit
     *
     * @throws InterruptedException
     * if the current thread was interrupted
     */
    @Throws(InterruptedException::class)
    fun await(timeout: Long, unit: TimeUnit?): Boolean {
        runBlocking {
            try {
                withTimeout(TimeUnit.MILLISECONDS.convert(timeout, unit)) {
                    future.await()
                    return@withTimeout true
                }.let {
                    return@runBlocking it
                }
            } catch (e: TimeoutCancellationException) {
                return@runBlocking false
            }
        }.let {
            return it
        }
    }
}