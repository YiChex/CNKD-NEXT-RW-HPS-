package net.rwhps.server.plugin.v2

import net.rwhps.server.data.bean.internal.BeanPluginInfo
import net.rwhps.server.plugin.V2PluginCapabilities
import net.rwhps.server.plugin.api.event.GameStartedEvent
import net.rwhps.server.plugin.api.CnkdPlugin
import net.rwhps.server.plugin.api.PluginContext
import net.rwhps.server.util.file.FileUtils
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class V2PluginEventIsolationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `v2 contexts deliver events on separate ordered plugin workers`() {
        val slowScope = PluginResourceScope("slow.events")
        val fastScope = PluginResourceScope("fast.events")
        val slow = context("slow.events", slowScope)
        val fast = context("fast.events", fastScope)
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val fastCompleted = CountDownLatch(1)

        slow.events().subscribe(GameStartedEvent::class.java) {
            assertTrue(Thread.currentThread().name.startsWith("PluginV2-Event-slow.events-"))
            slowStarted.countDown()
            releaseSlow.await(5, TimeUnit.SECONDS)
        }
        fast.events().subscribe(GameStartedEvent::class.java) {
            assertTrue(Thread.currentThread().name.startsWith("PluginV2-Event-fast.events-"))
            fastCompleted.countDown()
        }

        try {
            slow.publishGameStarted()
            assertTrue(slowStarted.await(2, TimeUnit.SECONDS))
            fast.publishGameStarted()
            assertTrue(fastCompleted.await(2, TimeUnit.SECONDS))
        } finally {
            releaseSlow.countDown()
            slow.deactivate()
            fast.deactivate()
            slowScope.close()
            fastScope.close()
        }
    }

    @Test
    fun `event dispatcher stops before plugin disable callback`() {
        val eventStarted = CountDownLatch(1)
        val eventFinished = CountDownLatch(1)
        val disableObservedFinishedEvent = AtomicBoolean(false)
        val plugin = object: CnkdPlugin {
            override fun onEnable(context: PluginContext) {
                context.events().subscribe(GameStartedEvent::class.java) {
                    eventStarted.countDown()
                    try {
                        CountDownLatch(1).await(5, TimeUnit.SECONDS)
                    } finally {
                        eventFinished.countDown()
                    }
                }
            }

            override fun onDisable() {
                disableObservedFinishedEvent.set(eventFinished.count == 0L)
            }
        }
        val loader = URLClassLoader(emptyArray(), javaClass.classLoader)
        val runtime = V2PluginRuntime(
            descriptor("shutdown.events"),
            plugin,
            loader,
            FileUtils(tempDir.resolve("shutdown.events").toFile())
        )
        val adapter = V2PluginAdapter(runtime)

        try {
            adapter.onEnable()
            runtime.context.publishGameStarted()
            assertTrue(eventStarted.await(2, TimeUnit.SECONDS))
            adapter.onDisable()
            assertTrue(eventFinished.await(2, TimeUnit.SECONDS))
            assertTrue(disableObservedFinishedEvent.get())
            assertTrue(runtime.runtimeMetrics.eventCallbackFailureCount() == 0L)
        } finally {
            adapter.onDisable()
            loader.close()
        }
    }

    @Test
    fun `actual v2 event service drops only overflow from its own queue`() {
        val scope = PluginResourceScope("pressure.events")
        val context = context("pressure.events", scope)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val callbackThread = AtomicReference<String>()
        val publisherThread = Thread.currentThread().name

        context.events().subscribe(GameStartedEvent::class.java) {
            callbackThread.compareAndSet(null, Thread.currentThread().name)
            if (firstStarted.count > 0L) {
                firstStarted.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            }
        }

        try {
            context.publishGameStarted()
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            repeat(70) { context.publishGameStarted() }

            assertTrue(callbackThread.get().startsWith("PluginV2-Event-pressure.events-"))
            assertTrue(callbackThread.get() != publisherThread)
            assertTrue(context.runtimeMetrics.eventNotificationCount() == 65L)
            assertTrue(context.runtimeMetrics.droppedEventNotificationCount() == 6L)
            assertTrue(context.runtimeMetrics.eventQueueMaxDepth() == PluginEventDispatcher.DEFAULT_QUEUE_CAPACITY)
            assertTrue(context.runtimeMetrics.healthSnapshot().level == "DEGRADED")
        } finally {
            releaseFirst.countDown()
            context.deactivate()
            scope.close()
        }
    }

    private fun context(id: String, scope: PluginResourceScope): V2PluginContext {
        return V2PluginContext(
            BeanPluginInfo(
                name = id,
                internalName = id,
                author = "test",
                main = "example.TestPlugin",
                description = "fixture",
                version = "1.0.0",
                supportedVersions = ">= 1.2.0",
                apiVersion = 2,
                capabilities = listOf(V2PluginCapabilities.GAME_EVENTS)
            ),
            tempDir.resolve(id),
            scope
        )
    }

    private fun descriptor(id: String) = BeanPluginInfo(
        name = id,
        internalName = id,
        author = "test",
        main = "example.TestPlugin",
        description = "fixture",
        version = "1.0.0",
        supportedVersions = ">= 1.2.0",
        apiVersion = 2,
        capabilities = listOf(V2PluginCapabilities.GAME_EVENTS)
    )
}
