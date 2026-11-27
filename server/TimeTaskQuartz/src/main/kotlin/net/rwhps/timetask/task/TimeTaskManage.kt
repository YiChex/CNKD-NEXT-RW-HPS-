/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.timetask.task

import net.rwhps.timetask.run.RunnableRun
import org.quartz.*
import org.quartz.CronScheduleBuilder.cronSchedule
import org.quartz.impl.StdSchedulerFactory
import java.util.*
import java.util.Properties


/**
 * 定时任务管理, 基于 Quartz
 *
 * @property scheduler Scheduler
 * @author Dr (dr@der.kim)
 */
@Suppress("UNUSED")
class TimeTaskManage {

    private val scheduler: Scheduler = StdSchedulerFactory(
        Properties().apply {
            // Start mode uses only a handful of recurring tasks, so 2 scheduler threads
            // are enough and save several MB of idle thread-stack memory.
            setProperty("org.quartz.threadPool.threadCount", "2")
            setProperty("org.quartz.threadPool.threadPriority", "4")
            setProperty("org.quartz.scheduler.skipUpdateCheck", "true")
            setProperty("org.quartz.scheduler.makeSchedulerThreadDaemon", "true")
            setProperty("org.quartz.threadPool.makeThreadsDaemons", "true")
        }
    ).scheduler

    init {
        this.scheduler.start()
    }


    fun shutdownNow() {
        scheduler.shutdown()
    }

    /**
     * 创建一个定时计时器
     * @param name 名字
     * @param group 组
     * @param description 描述
     * @param startTime 何时开始
     * @param runnable Runnable
     */
    fun addCountdown(name: String, group: String, description: String, startTime: Long, runnable: Runnable) {
        val key = JobKey(name, group)
        val jobDetail = JobBuilder.newJob(RunnableRun::class.java).withIdentity(key).build()
        val dataMap = JobDataMap()
        // A non-durable Quartz job is removed automatically after its one-shot
        // trigger completes. Removing by JobKey here can delete a replacement
        // scheduled from inside the callback with the same logical task name.
        dataMap["run"] = { runnable.run() }

        val trigger: Trigger = TriggerBuilder.newTrigger().usingJobData(dataMap).withDescription(description).withIdentity(name, group)
            //.withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInMilliseconds(time).withRepeatCount(0))
            .startAt(Date(startTime)).build()
        scheduler.scheduleJob(jobDetail, trigger)
    }

    /**
     * 创建一个定时循环计时器
     * @param name 名字
     * @param group 组
     * @param description 描述
     * @param intervalTime 执行间隔
     * @param startTime 何时开始
     * @param runnable Runnable
     */
    fun addTimedTask(name: String, group: String, description: String, startTime: Long, intervalTime: Long, runnable: Runnable) {
        val key = JobKey(name, group)
        val jobDetail = JobBuilder.newJob(RunnableRun::class.java).withIdentity(key).build()
        val dataMap = JobDataMap()
        dataMap["run"] = { runnable.run() }

        val trigger: Trigger = TriggerBuilder.newTrigger().usingJobData(dataMap).withDescription(description).withIdentity(name, group)
            .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInMilliseconds(intervalTime).repeatForever())
            .startAt(Date(startTime)).build()
        scheduler.scheduleJob(jobDetail, trigger)
    }

    /**
     * 创建一个定时循环计时器
     * @param name 名字
     * @param group 组
     * @param description 描述
     * @param corn 执行间隔
     * @param runnable Runnable
     */
    fun addTimedTask(name: String, group: String, description: String, corn: String, runnable: Runnable) {
        val key = JobKey(name, group)
        val jobDetail = JobBuilder.newJob(RunnableRun::class.java).withIdentity(key).build()
        val dataMap = JobDataMap()
        dataMap["run"] = { runnable.run() }

        val trigger: Trigger = TriggerBuilder.newTrigger().usingJobData(dataMap).withDescription(description).withIdentity(name, group)
            .withSchedule(cronSchedule(corn)).build()
        scheduler.scheduleJob(jobDetail, trigger)
    }

    /**
     * 查找是否有对应的任务
     *
     * @param jobKey 任务 [JobKey] 实例
     * @return 是否存在
     */
    fun contains(jobKey: JobKey): Boolean {
        return scheduler.checkExists(jobKey)
    }

    /**
     * 查找是否有对应的任务
     *
     * @param name 任务名字
     * @param group 任务组
     * @return 是否存在
     */
    fun contains(name: String, group: String): Boolean {
        return contains(JobKey(name, group))
    }

    /**
     * 暂停对应的任务
     *
     * @param jobKey 任务 [JobKey] 实例
     */
    fun pause(jobKey: JobKey) {
        scheduler.pauseJob(jobKey)
    }

    /**
     * 暂停对应的任务
     *
     * @param name 任务名字
     * @param group 任务组
     */
    fun pause(name: String, group: String) {
        pause(JobKey(name, group))
    }

    /**
     * 取消暂停对应的任务
     *
     * @param jobKey 任务 [JobKey] 实例
     */
    fun unPause(jobKey: JobKey) {
        scheduler.resumeJob(jobKey)
    }

    /**
     * 取消暂停对应的任务
     *
     * @param name 任务名字
     * @param group 任务组
     */
    fun unPause(name: String, group: String) {
        unPause(JobKey(name, group))
    }

    /**
     * 取消对应的任务
     *
     * @param jobKey 任务 [JobKey] 实例
     * @return 是否取消
     */
    fun remove(jobKey: JobKey): Boolean {
        pause(jobKey)
        scheduler.unscheduleJob(TriggerKey.triggerKey(jobKey.name, jobKey.group))
        return scheduler.deleteJob(jobKey)
    }

    /**
     * 取消对应的任务
     *
     * @param name 任务名字
     * @param group 任务组
     * @return 是否取消
     */
    fun remove(name: String, group: String): Boolean {
        return remove(JobKey(name, group))
    }

}
