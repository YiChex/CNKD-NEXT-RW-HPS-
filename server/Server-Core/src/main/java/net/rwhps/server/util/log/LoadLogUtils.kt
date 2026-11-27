/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.log

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.Time

/**
 *
 *
 * @date 2023/12/30 16:38
 * @author Dr (dr@der.kim)
 */
object LoadLogUtils {
    /**
     * 加载-打印
     *
     * 会输出: 正在加载~ [Start/OK/ERROR
     * ]
     * @param input String
     * @param run 待执行
     */
    fun loadStatusLog(input: String, run:()->Unit) {
        printOverride(Data.i18NBundle.getinput(input, "Start"), false)
        try {
            run()
            printOverride(Data.i18NBundle.getinput(input, "OK"))
        } catch (e: Exception) {
            printOverride(Data.i18NBundle.getinput(input, "ERROR"))
            Log.track(input, e)
        }
    }

    private fun printOverride(text: String, nextLine: Boolean = true) {
        val outText = "[${Time.getMilliFormat(1)}] " + ColorCodes.formatColors("$text&fr")
        if (nextLine) {
            Data.privateReader.terminal.writer().println("\r$outText   ")
        } else {
            Data.privateReader.terminal.writer().print(outText)
        }
    }
}