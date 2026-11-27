/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */
package net.rwhps.server.util.game

import kotlinx.coroutines.*
import net.rwhps.server.func.Control
import net.rwhps.server.game.event.core.AbstractEvent
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.annotations.core.EventOnlyRead
import net.rwhps.server.util.inline.ifNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @author Dr (dr@der.kim)
 */
class Events {
    private val eventData = ObjectMap<Any, Seq<(Any) -> Any?>>()
    private val eventMonitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun <T> addEvent(type: Class<T>, listener: (T) -> Unit) {
        addEventScoped(false, type, listener)
    }

    fun <T> addEvent(async: Boolean, type: Class<T>, listener: (T) -> Unit) {
        addEventScoped(async, type, listener)
    }

    fun <T> addEventScoped(type: Class<T>, listener: (T) -> Unit): AutoCloseable {
        return addEventScoped(false, type, listener)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> addEventScoped(async: Boolean, type: Class<T>, listener: (T) -> Unit): AutoCloseable {
        val registered: (Any) -> Any? = if (async || type.getAnnotation(EventOnlyRead::class.java) != null) {
            { value: Any ->
                eventMonitorScope.launch {
                    listener(value as T)
                }
            }
        } else {
            { value: Any ->
                listener(value as T)
                null
            }
        }
        val listeners = eventData[type, { Seq() }]
        listeners.add(registered)
        return EventRegistration { listeners.remove(registered) }
    }

    fun addEvent(type: Any, listener: () -> Unit) {
        addEventScoped(type, listener)
    }

    fun addEventScoped(type: Any, listener: () -> Unit): AutoCloseable {
        val registered: (Any) -> Any? = { listener() }
        val listeners = eventData[type, { Seq() }]
        listeners.add(registered)
        return EventRegistration { listeners.remove(registered) }
    }

    fun <T> remove(type: Class<T>) {
        eventData[type, { Seq() }].clear()
    }

    fun <T> fire(type1: Class<*>, type: T) {
        val async = Seq<Job>()

        val eventType = eventData[type as Any]
        eventType?.eachAll { e: (Any) -> Any? -> runAsync(async, e, type) }

        val eventType1 = eventData[type1]
        eventType1?.eachAll { e: (Any) -> Any? -> runAsync(async, e, type) }

        runBlocking {
            async.forEach {
                it.join()
            }
        }
    }

    private fun <T> runAsync(async: Seq<Job>, e: (T) -> Any?, type: T) {
        if (type is AbstractEvent) {
            if (type.status() == Control.EventNext.CONTINUE) {
                e(type).ifNull({
                    if (it is Job) {
                        async.add(it)
                    }
                })
            }
        }
    }

    private class EventRegistration(private val remove: () -> Unit): AutoCloseable {
        private val active = AtomicBoolean(true)

        override fun close() {
            if (active.compareAndSet(true, false)) {
                remove()
            }
        }
    }
}
