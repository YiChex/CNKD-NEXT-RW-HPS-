/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.core.thread

import net.rwhps.server.data.global.Data
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.Time
import net.rwhps.server.util.concurrent.threads.GetNewThreadPool
import net.rwhps.server.util.log.Log
import net.rwhps.timetask.task.TimeTaskManage
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * TimeTask / Thread Core
 * @author Dr (dr@der.kim)
 */
object Threads {
    private val CORE_THREAD: ThreadPoolExecutor = GetNewThreadPool.getNewFixedThreadPool(
        coreThreadPoolSize(),
        "Core-",
        coreQueueCapacity(),
        30L,
        Data.pureStartMode
    )
    private var CORE_NET_THREAD: ThreadPoolExecutor = GetNewThreadPool.getNewFixedThreadPool(
        coreNetThreadPoolSize(),
        "Core-Net-",
        coreNetQueueCapacity(),
        30L,
        Data.pureStartMode
    )
    private val SERVICE = TimeTaskManage() // 10 Thread
    private val OTHER_THREAD: ThreadPoolExecutor = GetNewThreadPool.getNewFixedThreadPool(
        otherThreadPoolSize(),
        "Other-",
        otherQueueCapacity(),
        30L,
        true
    )

    /** Execute runnable on exit  */
    private val SAVE_POOL = Seq<Runnable>()

    internal val dataAutoSave = Seq<Runnable>(8)

    /**
     * 关闭全部线程池
     */
    @JvmStatic
    fun close() {
        CORE_THREAD.shutdownNow()
        CORE_NET_THREAD.shutdownNow()
        SERVICE.shutdownNow()
        OTHER_THREAD.shutdownNow()
    }

    /**
     * 关闭网络线程池
     */
    @JvmStatic
    fun closeNet() {
        CORE_NET_THREAD.shutdownNow()
        CORE_NET_THREAD = GetNewThreadPool.getNewFixedThreadPool(
            coreNetThreadPoolSize(),
            "Core-Net-",
            coreNetQueueCapacity(),
            30L,
            Data.pureStartMode
        )
    }

    /**
     * 杂物线程 [run]
     *
     * @param run 待执行
     */
    @JvmStatic
    fun newThreadOther(run: Runnable) {
        OTHER_THREAD.execute(run)
    }

    /**
     * 在核心线程池执行 [run]
     *
     * @param run 待执行
     */
    @JvmStatic
    fun newThreadCore(run: Runnable) {
        CORE_THREAD.execute(run)
    }

    /**
     * 在网络线程池执行 [run]
     *
     * @param run 待执行
     */
    @JvmStatic
    fun newThreadCoreNet(run: Runnable) {
        CORE_NET_THREAD.execute(run)
    }

    /**
     * 在退出保存线程池执行 [run]
     * 此线程仅在退出时开始运行
     *
     * @param run 待执行
     */
    @JvmStatic
    fun addSavePool(run: Runnable) {
        SAVE_POOL.add(run)
    }

    @JvmStatic
    fun runSavePool() {
        SAVE_POOL.toList().forEachIndexed { index, task ->
            try {
                task.run()
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) {
                    throw error
                }
                Log.error("Shutdown save callback #$index failed; remaining callbacks will continue", error)
            }
        }
    }


    /**
     * Create a countdown timer
     * @param run       Runnable
     * @param endTime   How long to execute
     * @param timeUnit  Time Unit
     * @param taskFlag  NameID
     * @return          ScheduledFuture<*>
     */
    @JvmStatic
    internal fun newCountdown(taskFlag: CallTimeTask, endTime: Int, timeUnit: TimeUnit, run: Runnable) {
        newCountdown(taskFlag.name, taskFlag.group, taskFlag.description, endTime, timeUnit, run)
    }

    /**
     * 创建一个倒数计时器
     * @param taskFlagName          Name
     * @param taskFlagGroup         Group
     * @param taskFlagDescription   Description
     * @param endTime               How long to execute
     * @param timeUnit              Time Unit
     * @param run                   Runnable
     */
    @JvmStatic
    fun newCountdown(
        taskFlagName: String, taskFlagGroup: String, taskFlagDescription: String, endTime: Int, timeUnit: TimeUnit, run: Runnable
    ) {
        SERVICE.addCountdown(
                taskFlagName,
                taskFlagGroup,
                taskFlagDescription,
                Time.concurrentMillis() + TimeUnit.MILLISECONDS.convert(endTime.toLong(), timeUnit),
                run
        )
    }

    /**
     * 创建一个定时计时器
     * @param run Runnable
     * @param startTime 多长时间后开始
     * @param intervalTime 执行间隔
     * @param timeUnit Time Unit
     * @param taskFlag NameID
     * @return ScheduledFuture<*>
     */
    @JvmStatic
    fun newTimedTask(taskFlag: CallTimeTask, startTime: Int, intervalTime: Int, timeUnit: TimeUnit, run: Runnable) {
        newTimedTask(taskFlag.name, taskFlag.group, taskFlag.description, startTime, intervalTime, timeUnit, run)
    }

    /**
     * 创建一个倒数计时器
     * @param taskFlagName          Name
     * @param taskFlagGroup         Group
     * @param taskFlagDescription   Description
     * @param startTime             How long to execute
     * @param intervalTime          Repeat after time interval
     * @param timeUnit              Time Unit
     * @param run                   Runnable
     */
    @JvmStatic
    fun newTimedTask(
        taskFlagName: String,
        taskFlagGroup: String,
        taskFlagDescription: String,
        startTime: Int,
        intervalTime: Int,
        timeUnit: TimeUnit,
        run: Runnable
    ) {
        SERVICE.addTimedTask(
                taskFlagName,
                taskFlagGroup,
                taskFlagDescription,
                Time.concurrentMillis() + TimeUnit.MILLISECONDS.convert(startTime.toLong(), timeUnit),
                TimeUnit.MILLISECONDS.convert(intervalTime.toLong(), timeUnit),
                run
        )
    }

    /**
     * Check if there is a task
     * @param taskFlag NameID
     * @return Boolean
     */
    @JvmStatic
    internal fun containsTimeTask(taskFlag: CallTimeTask): Boolean {
        return containsTimeTask(taskFlag.name, taskFlag.group)
    }

    /**
     * Check if there is a task
     * @param taskFlagName          Name
     * @param taskFlagGroup         Group
     * @return Boolean
     */
    @JvmStatic
    fun containsTimeTask(taskFlagName: String, taskFlagGroup: String): Boolean {
        return SERVICE.contains(taskFlagName, taskFlagGroup)
    }

    /**
     * 暂停对应的任务
     *
     * @param taskFlag NameID
     */
    @JvmStatic
    internal fun pause(taskFlag: CallTimeTask) {
        pause(taskFlag.name, taskFlag.group)
    }

    /**
     * 暂停对应的任务
     *
     * @param name 任务名字
     * @param group 任务组
     */
    @JvmStatic
    fun pause(name: String, group: String) {
        SERVICE.pause(name, group)
    }

    /**
     * 取消暂停对应的任务
     *
     * @param taskFlag NameID
     */
    @JvmStatic
    internal fun unPause(taskFlag: CallTimeTask) {
        unPause(taskFlag.name, taskFlag.group)
    }

    /**
     * 取消暂停对应的任务
     *
     * @param name 任务名字
     * @param group 任务组
     */
    @JvmStatic
    fun unPause(name: String, group: String) {
        SERVICE.unPause(name, group)
    }

    /**
     * Close Task
     * @param taskFlag NameID
     * @param run Runnable?
     * @return Boolean
     */
    @JvmStatic
    @JvmOverloads
    fun closeTimeTask(taskFlag: CallTimeTask, run: Runnable? = null): Boolean {
        return closeTimeTask(taskFlag.name, taskFlag.group, run)
    }

    /**
     * Close Task
     * @param taskFlagName          Name
     * @param taskFlagGroup         Group
     * @param run Runnable?
     * @return Boolean
     */
    @JvmStatic
    @JvmOverloads
    fun closeTimeTask(taskFlagName: String, taskFlagGroup: String, run: Runnable? = null): Boolean {
        val flag = SERVICE.remove(taskFlagName, taskFlagGroup)
        if (flag) {
            run?.run()
        }
        return flag
    }

    fun buildDiagnostics(): List<String> {
        return listOf(
            formatPoolState("Core", CORE_THREAD),
            formatPoolState("Core-Net", CORE_NET_THREAD),
            formatPoolState("Other", OTHER_THREAD)
        )
    }

    private fun coreThreadPoolSize(): Int = if (Data.pureStartMode) 1 else 2

    private fun coreNetThreadPoolSize(): Int = if (Data.pureStartMode) 1 else 2

    private fun otherThreadPoolSize(): Int = if (Data.pureStartMode) 2 else 4

    private fun coreQueueCapacity(): Int = if (Data.pureStartMode) 64 else 256

    private fun coreNetQueueCapacity(): Int = if (Data.pureStartMode) 64 else 256

    private fun otherQueueCapacity(): Int = if (Data.pureStartMode) 96 else 256

    private fun formatPoolState(name: String, executor: ThreadPoolExecutor): String {
        val backpressure = (executor as? GetNewThreadPool.ObservedThreadPoolExecutor)
            ?.buildBackpressureDiagnostics()
            ?.let { ", $it" }
            ?: ""
        return "[ThreadPool:$name] poolSize=${executor.poolSize}, active=${executor.activeCount}, queued=${executor.queue.size}, remainingCapacity=${executor.queue.remainingCapacity()}, completed=${executor.completedTaskCount}, largest=${executor.largestPoolSize}$backpressure"
    }
}
