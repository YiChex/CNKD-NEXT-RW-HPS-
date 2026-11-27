/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.privately

import net.rwhps.server.data.global.Data
import net.rwhps.server.io.input.DisableSyncByteArrayInputStream
import net.rwhps.server.io.output.ByteArrayOutputStream
import net.rwhps.server.util.compression.CompressionEncoderUtils
import net.rwhps.server.util.file.FileUtils
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 处理 游戏资源文件, 适配无头
 *
 * @date  2023/6/17 19:47
 * @author Dr (dr@der.kim)
 */
internal object FakeHessRes {
    fun start() {
        val zip = CompressionEncoderUtils.zipStream()
        val ogg = FileUtils.getFile("min.ogg").readFileByte()
        FileUtils.getFolder(Data.Plugin_GameCore_Data_Path).toFile("Game-Assets.zip").zipDecoder.getZipAllBytes().eachAll { k, v ->
            var bytes = v
            when {
                k.endsWith(".png") -> {
                    val image0 = ImageIO.read(DisableSyncByteArrayInputStream(v))
                    val image = BufferedImage(image0.width, image0.height, BufferedImage.TYPE_INT_ARGB)

                    val g2d = image.createGraphics()
                    g2d.color = Color(0, 0, 0, 0)
                    g2d.fillRect(0, 0, image0.width, image0.height)
                    g2d.dispose()

                    val outputFile = ByteArrayOutputStream()
                    ImageIO.write(image, "png", outputFile)
                    bytes = outputFile.toByteArray()
                }
                k.endsWith(".jpg") -> {
                    val image0 = ImageIO.read(DisableSyncByteArrayInputStream(v))
                    val image = BufferedImage(image0.width, image0.height, BufferedImage.TYPE_INT_ARGB)

                    val g2d = image.createGraphics()
                    g2d.color = Color(0, 0, 0, 0)
                    g2d.fillRect(0, 0, image0.width, image0.height)
                    g2d.dispose()

                    val outputFile = ByteArrayOutputStream()
                    ImageIO.write(image, "jpg", outputFile)
                    bytes = outputFile.toByteArray()
                }
                k.endsWith(".ogg") -> bytes = ogg
            }
            zip.addCompressBytes(k, bytes)
        }
        FileUtils.getFile("Game-Assets-out.zip").writeFileByte(zip.flash())
        val zip0 = CompressionEncoderUtils.zipStream()
        FileUtils.getFolder(Data.Plugin_GameCore_Data_Path).toFile("Game-Res.zip").zipDecoder.getZipAllBytes().eachAll { k, v ->
            var bytes = v
            when {
                k.endsWith(".png") -> {
                    val image0 = ImageIO.read(DisableSyncByteArrayInputStream(v))
                    val image = BufferedImage(image0.width, image0.height, BufferedImage.TYPE_INT_ARGB)

                    val g2d = image.createGraphics()
                    g2d.color = Color(0, 0, 0, 0)
                    g2d.fillRect(0, 0, image0.width, image0.height)
                    g2d.dispose()

                    val outputFile = ByteArrayOutputStream()
                    ImageIO.write(image, "png", outputFile)
                    bytes = outputFile.toByteArray()
                }
                k.endsWith(".jpg") -> {
                    val image0 = ImageIO.read(DisableSyncByteArrayInputStream(v))
                    val image = BufferedImage(image0.width, image0.height, BufferedImage.TYPE_INT_ARGB)

                    val g2d = image.createGraphics()
                    g2d.color = Color(0, 0, 0, 0)
                    g2d.fillRect(0, 0, image0.width, image0.height)
                    g2d.dispose()

                    val outputFile = ByteArrayOutputStream()
                    ImageIO.write(image, "jpg", outputFile)
                    bytes = outputFile.toByteArray()
                }
                k.endsWith(".ogg") -> bytes = ogg
            }
            zip0.addCompressBytes(k, bytes)
        }
        FileUtils.getFile("Game-Res-out.zip").writeFileByte(zip0.flash())
    }
}