/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.log.ex

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.log.Log
import java.io.FilterInputStream
import java.io.IOException
import java.util.*

/**
 *
 *
 * @date 2024/5/2 下午3:33
 * @author Dr (dr@der.kim)
 */
object PrintEx {
    fun waitLicense(init: ()->Unit, ok: ()->Unit, no: ()->Unit) {
        init()

        val chineseMode = Data.serverCountry != "EN"
        Log.clog(if (chineseMode) "输入 Yes 确认继续，输入 No 取消" else "Agree to enter : Yes , Otherwise please enter : No")
        Data.privateOut.print(if (chineseMode) "请输入 (Yes/No) > " else "Please Enter (Yes/No) > ")

        Scanner(object: FilterInputStream(System.`in`) {
            @Throws(IOException::class)
            override fun close() {
                //do nothing
            }
        }).use {
            while (true) {
                val text = it.nextLine()
                if (text.equals("Yes", ignoreCase = true)) {
                    Log.clog(if (chineseMode) "已确认" else "Thanks !")
                    ok()
                    return
                } else if (text.equals("No", ignoreCase = true)) {
                    Log.clog(if (chineseMode) "已取消" else "Thanks !")
                    no()
                } else {
                    Log.clog(if (chineseMode) "输入无效，请重新输入" else "Re Enter")
                    Data.privateOut.print(if (chineseMode) "请输入 (Yes/No) > " else "Please Enter (Yes/No) > ")
                }
            }
        }
    }
}
