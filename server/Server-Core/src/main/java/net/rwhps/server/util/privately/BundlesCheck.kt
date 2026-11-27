/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.privately

import net.rwhps.server.Main
import net.rwhps.server.data.global.Data
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.file.load.I18NBundle
import net.rwhps.server.util.inline.readFileListString
import java.io.FileOutputStream

/**
 * 用于处理语言文件的一致性
 *
 * @date 2024/1/31 11:14
 * @author Dr (dr@der.kim)
 */
internal object BundlesCheck {
    fun start() {
        val cn = I18NBundle(Main::class.java.getResourceAsStream("/bundles/HPS_zh_CN.properties")!!)
        val hk  = I18NBundle(Main::class.java.getResourceAsStream("/bundles/HPS_zh_HK.properties")!!)
        val ru  = I18NBundle(Main::class.java.getResourceAsStream("/bundles/HPS_ru_RU.properties")!!)
        val en  = I18NBundle(Main::class.java.getResourceAsStream("/bundles/HPS_en_US.properties")!!)

        write(FileUtils.getFile("HPS_zh_HK.properties").writeByteOutputStream(), hk, cn)
        write(FileUtils.getFile("HPS_ru_RU.properties").writeByteOutputStream(), ru, cn)
        write(FileUtils.getFile("HPS_en_US.properties").writeByteOutputStream(), en, cn)
    }

    private fun write(fileOutputStream: FileOutputStream, sor: I18NBundle, diff: I18NBundle) {
        Main::class.java.getResourceAsStream("/bundles/HPS_zh_CN.properties")!!.readFileListString().eachAll {
            if (it.startsWith("#")) {
                fileOutputStream.write("$it${Data.LINE_SEPARATOR}".toByteArray())
            } else if (it.contains("=")) {
                val array = it.split("=")
                //val text = sor.languageData[array[0], diff.languageData[array[0]]!!]
                val text = ""
                var wrMsg = "${array[0]}="
                val size = text.lines().size
                if (size > 1) {
                    for ((index, t) in text.lines().withIndex()) {
                        if (index != 0) {
                            wrMsg += String.format("%1$-" + (array[0].length+1) + "s", "")
                        }
                        wrMsg += t
                        if (index != size-1) {
                            wrMsg += "\\n\\"
                        }
                        wrMsg += Data.LINE_SEPARATOR
                    }
                } else {
                    wrMsg += text
                    wrMsg += Data.LINE_SEPARATOR
                }

                fileOutputStream.write(wrMsg.toByteArray())
            }
        }
        fileOutputStream.flush()
    }
}