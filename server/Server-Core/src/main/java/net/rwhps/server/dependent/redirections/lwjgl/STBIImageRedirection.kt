/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.dependent.redirections.lwjgl

import net.rwhps.server.util.log.Log

import net.rwhps.asm.api.replace.RedirectionReplace
import net.rwhps.asm.data.MethodTypeInfoValue
import net.rwhps.asm.util.ByteBufferInputStream
import net.rwhps.server.util.annotations.mark.AsmMark
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.IntBuffer
import javax.imageio.ImageIO

@AsmMark.ClassLoaderCompatible
enum class STBIImageRedirection: RedirectionReplace {
    INSTANCE;

    @Throws(Throwable::class)
    override fun invoke(obj: Any, desc: String, type: Class<*>, vararg args: Any?): Any {
        val buffer = args[0] as ByteBuffer
        val x = args[1] as IntBuffer
        val y = args[2] as IntBuffer
        val channels_in_file = args[3] as IntBuffer
        val desired_channels = args[4] as Int
        val result = ByteBuffer.wrap(
                ByteArray(
                        x[x.position()] * y[y.position()] * if (desired_channels != 0) desired_channels else channels_in_file[channels_in_file.position()]
                )
        )
        val image = readImage(buffer)
        x.put(0, image.width)
        y.put(0, image.height)
        // TODO: check discrepancies between desired_channels and actual
        channels_in_file.put(0, desired_channels)
        return result
    }

    private fun readImage(buffer: ByteBuffer): BufferedImage {
        val position = buffer.position()
        var image: BufferedImage? = null
        try {
            image = ImageIO.read(ByteBufferInputStream(buffer))
        } catch (e: IOException) {
            Log.error("[D-30] Unexpected exception", e)
        }
        buffer.position(position)
        return image ?: DUMMY
    }

    companion object {
        val DESC = MethodTypeInfoValue("org/lwjgl/stb/STBImage", "stbi_load_from_memory", "(Ljava/nio/ByteBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)Ljava/nio/ByteBuffer;")

        private val DUMMY = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
    }
}