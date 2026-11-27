/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import net.rwhps.server.game.event.core.EventListenerHost
import net.rwhps.server.util.ReflectionUtils
import net.rwhps.server.util.annotations.core.EventListenerHandler
import net.rwhps.server.util.concurrent.fature.AbstractFuture
import net.rwhps.server.util.concurrent.fature.EventFuture
import net.rwhps.server.util.game.Events
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EventManage 的主要实现
 *
 * @date 2023/7/5 14:44
 * @author Dr (dr@der.kim)
 */
abstract class AbstractEventManage(private val eventClass: Class<*>) {
    protected val eventData = Events()
    private val eventRunScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    /**
     * 注册一个监听器
     *
     * @param eventListen EventListenerHost
     */
    fun registerListener(eventListen: EventListenerHost) {
        registerListenerScoped(eventListen)
    }

    fun registerListenerScoped(eventListen: EventListenerHost): AutoCloseable {
        val registrations = ArrayList<AutoCloseable>()
        try {
            // 判断是不是继承事件, 并扫描有效类 (注解)
            ReflectionUtils.getAllDeclaredMethods(eventListen::class.java).forEach { method ->
                method.getAnnotation(EventListenerHandler::class.java) ?: return@forEach
                val parameterTypes = method.parameterTypes
                if (parameterTypes.size == 1 && eventClass.isAssignableFrom(parameterTypes[0]) && !Modifier.isAbstract(
                            parameterTypes[0].modifiers
                    )) {
                    ReflectionUtils.makeAccessible(method)

                    registrations.add(eventData.addEventScoped(parameterTypes[0]) { value ->
                        try {
                            ReflectionUtils.invokeMethod(method, eventListen, value)
                        } catch (error: Throwable) {
                            /*
                             * P1-1: `catch (Exception)` let an Error thrown by a plugin listener
                             * (NoClassDefFoundError, LinkageError, AssertionError, ...) escape this
                             * inline dispatch step. Events.fire() (util/game/Events.kt:73-87) runs
                             * non-@EventOnlyRead listeners synchronously, so the Error propagated to
                             * the caller's `fire(...).await()` and killed the core thread, skipping
                             * every remaining listener. Route all non-fatal throwables through
                             * handleException, matching the V2 per-listener guard
                             * (plugin/v2/V2PluginContext.kt:412-422).
                             *
                             * VirtualMachineError (OutOfMemoryError, StackOverflowError, ...) and
                             * ThreadDeath stay fatal and are rethrown unchanged so a JVM-fatal
                             * condition is never downgraded to a logged-and-ignored listener
                             * failure. This mirrors the repo-wide guard used by
                             * PluginManage.rethrowFatal (util/file/plugin/PluginManage.kt:297-301),
                             * V2PluginContext (plugin/v2/V2PluginContext.kt:413) and Threads
                             * (core/thread/Threads.kt:127), and it preserves today's behaviour for
                             * those two types (which currently escape this catch block too).
                             */
                            if (error is VirtualMachineError || error is ThreadDeath) throw error
                            eventListen.handleException(error)
                        }
                    })
                }
            }
        } catch (error: Throwable) {
            registrations.asReversed().forEach {
                runCatching { it.close() }
            }
            throw error
        }
        val active = AtomicBoolean(true)
        return AutoCloseable {
            if (active.compareAndSet(true, false)) {
                var firstFailure: Throwable? = null
                registrations.asReversed().forEach { registration ->
                    try {
                        registration.close()
                    } catch (error: Throwable) {
                        if (firstFailure == null) {
                            firstFailure = error
                        } else {
                            firstFailure?.addSuppressed(error)
                        }
                    }
                }
                firstFailure?.let { throw it }
            }
        }
    }

    protected fun fire0(type: Any): AbstractFuture<*> {
        return EventFuture(eventRunScope.async<Throwable?> {
            try {
                eventData.fire(type::class.java, type)
            } catch (error: Throwable) {
                /*
                 * P1-1: outer safety net. Widened from `Exception` to `Throwable` for the same
                 * reason as the per-listener guard above: an Error must not escape and kill the
                 * caller of `fire(...).await()`. This also covers consumer-style listeners
                 * registered through EventManage.registerListener / EventGlobalManage.registerListener,
                 * which go to Events.addEventScoped (util/game/Events.kt:62-67) and therefore have
                 * no per-listener guard at all.
                 *
                 * Non-fatal throwables keep the pre-existing contract: they are RETURNED as the
                 * Deferred's value (not thrown), so `await()` completes normally and callers such as
                 * GameVersionServer.kt:843 continue; `cause()` still exposes the failure and
                 * `sync()` still raises ExecutionException.
                 *
                 * VirtualMachineError / ThreadDeath are rethrown rather than returned, for three
                 * reasons: (1) it preserves today's behaviour for those types, which escape
                 * `catch (Exception)` and fail the Deferred so `await()` rethrows them; returning
                 * them instead would silently downgrade a JVM-fatal condition to a success value;
                 * (2) the per-listener guard above rethrows them, and a rethrow there is pointless
                 * if this outer layer converts the same error back into a value; (3) it matches the
                 * repo-wide convention (PluginManage.rethrowFatal:297-301, V2PluginContext:413,
                 * Threads:127).
                 */
                if (error is VirtualMachineError || error is ThreadDeath) throw error
                return@async error
            }
             return@async null
        })
    }
}
