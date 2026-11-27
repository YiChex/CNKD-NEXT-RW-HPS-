package net.rwhps.server.util.concurrent.fature

import kotlinx.coroutines.*
import net.rwhps.server.util.Time
import org.junit.jupiter.api.Test

/**
 *
 *
 * @date 2024/1/5 20:24
 * @author Dr (dr@der.kim)
 */
class EventFutureTest {
    private val eventRunScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun cancel() {
    }

    @Test
    fun isCancelled() {
    }

    @Test
    fun isDone() {
    }

    @Test
    fun get() {
    }

    @Test
    fun testGet() {
    }

    @Test
    fun cause() {
    }

    @Test
    fun sync() {
    }

    @Test
    fun await() {
        val fature = EventFuture(eventRunScope.async<Throwable?> {
            try {
                delay(1000)
            } catch (error: Exception) {
                return@async error
            }
            return@async null
        })
        println(Time.concurrentMillis())
        fature.await()
        println(Time.concurrentMillis())
    }

    @Test
    fun awaitUninterruptible() {
        val fature = EventFuture(eventRunScope.async<Throwable?> {
            try {
                delay(2000)
            } catch (error: Exception) {
                return@async error
            }
            return@async null
        })
        eventRunScope.async<Throwable?> {
            try {
                delay(500)
                fature.cancel(true)
            } catch (error: Exception) {
                return@async error
            }
            return@async null
        }
        fature.awaitUninterruptible()
    }

    @Test
    fun testAwait() {
    }
}