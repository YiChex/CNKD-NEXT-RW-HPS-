/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.concurrent.fature

import kotlinx.coroutines.*
import java.util.concurrent.*

/**
 * 为 Event 提供同步支持
 *
 * 默认的 Event 运行在独立的线程中, 为了预防Plugin卡死Main/Net
 *
 * @date 2023/9/2 15:18
 * @author Dr (dr@der.kim)
 */
class EventFuture<V>(future: Deferred<V>) : AbstractFuture<V>(future) {
    /**
     * D-14: this used to `throw ImplementedException("No")`, so any generic code that
     * treated an [EventFuture] as a standard [Future] (thread pools, adapters,
     * third-party libraries) crashed immediately. `get()` now blocks until the
     * coroutine completes and returns its value, matching the [Future] contract.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun get(): V? {
        await()
        return future.getCompleted()
    }

    /**
     * D-14: see [get]. Blocks up to the given timeout and reports a proper
     * [TimeoutException] instead of an unconditional failure.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Throws(TimeoutException::class)
    override fun get(timeout: Long, unit: TimeUnit): V {
        if (!super.await(timeout, unit)) {
            throw TimeoutException("Timed out waiting for event completion: $timeout $unit")
        }
        return future.getCompleted()
    }
}
