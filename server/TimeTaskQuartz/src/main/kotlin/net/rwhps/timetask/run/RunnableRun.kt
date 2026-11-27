/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.timetask.run

import org.quartz.Job
import org.quartz.JobExecutionContext

/**
 * 基于Runnable的任务器
 *
 * @author Dr (dr@der.kim)
 */
class RunnableRun: Job {
    override fun execute(context: JobExecutionContext) {
        @Suppress("UNCHECKED_CAST") (context.mergedJobDataMap["run"] as (() -> Unit))()
    }
}