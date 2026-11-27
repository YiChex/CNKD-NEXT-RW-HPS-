/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.dependent.redirections.fast

import net.rwhps.asm.api.replace.RedirectionReplace
import net.rwhps.server.dependent.redirections.lwjgl.LwjglClassProperties

/**
 *
 *  为Lwjgl提供的, 在高并发中快速返回数据, 避免在 ASM 框架中被 [HashMap] 影响效率
 *
 * @date 2024/1/12 10:35
 * @author Dr (dr@der.kim)
 */
object LwjglFastValueClass {
    val textureSize = System.getProperty(LwjglClassProperties.TEXTURE_SIZE, "1024").toInt()
    val fullScreen = System.getProperty(LwjglClassProperties.FULLSCREEN, "false").toBoolean()
    val screenWidth = System.getProperty(LwjglClassProperties.SCREEN_WIDTH, "800").toInt() // 1920
    val screenHeight = System.getProperty(LwjglClassProperties.SCREEN_HEIGHT, "600").toInt() // 1080

    object TextureSize : RedirectionReplace {
        override fun invoke(obj: Any, desc: String, type: Class<*>, vararg args: Any?): Int {
            return textureSize
        }
    }

    object FullScreen : RedirectionReplace {
        override fun invoke(obj: Any, desc: String, type: Class<*>, vararg args: Any?): Boolean {
            return fullScreen
        }
    }

    object ScreenWidth : RedirectionReplace {
        override fun invoke(obj: Any, desc: String, type: Class<*>, vararg args: Any?): Int {
            return screenWidth
        }
    }

    object ScreenHeight : RedirectionReplace {
        override fun invoke(obj: Any, desc: String, type: Class<*>, vararg args: Any?): Int {
            return screenHeight
        }
    }
}