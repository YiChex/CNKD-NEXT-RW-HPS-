/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.dependent.redirections

import net.rwhps.server.util.classload.GameModularReusableLoadClass
import net.rwhps.server.util.compression.CompressionDecoderUtils
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.game.GameStartInit
import net.rwhps.server.util.log.Log
import org.junit.jupiter.api.Test

/**
 *
 *
 * @date 2023/12/22 16:33
 * @author Dr (dr@der.kim)
 */
class ASMTest {
    @Test
    fun testASM() {
        try {
            // Start Hess Core
            val load = GameModularReusableLoadClass(
                    Thread.currentThread().contextClassLoader, Thread.currentThread().contextClassLoader.parent
            )
            // 加载游戏依赖
            CompressionDecoderUtils.zipAllReadStream(GameStartInit::class.java.getResourceAsStream("/libs.zip")!!).use {
                it.getSpecifiedSuffixInThePackage("jar", true).eachAll { _, v ->
                    load.addSourceJar(v)
                }
            }
            net.rwhps.asm.Test().a(load, load.classPathMapData["com/corrodinggames/rts/gameFramework/e/c"]!!, FileUtils.getFile("a.class").writeByteOutputStream())
            net.rwhps.asm.Test().b(load, load.classPathMapData["com/corrodinggames/rts/gameFramework/e/c"]!!, FileUtils.getFile("b.class").writeByteOutputStream())
            net.rwhps.asm.Test().c(load, load.classPathMapData["com/corrodinggames/rts/gameFramework/e/c"]!!, FileUtils.getFile("c.class").writeByteOutputStream())
            net.rwhps.asm.Test().d(load, load.classPathMapData["com/corrodinggames/rts/gameFramework/e/c"]!!, FileUtils.getFile("d.class").writeByteOutputStream())
        } catch (e: Exception) {
            Log.fatal(e)
        }
    }
}