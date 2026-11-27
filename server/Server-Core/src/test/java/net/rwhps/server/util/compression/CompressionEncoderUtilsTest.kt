package net.rwhps.server.util.compression

import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.input.DisableSyncByteArrayInputStream
import net.rwhps.server.util.ExtractUtils
import net.rwhps.server.util.log.Log
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * @date  2023/5/27 11:40
 * @author Dr (dr@der.kim)
 */
class CompressionEncoderUtilsTest {
    init {
        Log.set("ERROR")
    }

    @Test
    fun zipStream() {
        ExtractUtils.tryRunTest {
            val zip = CompressionEncoderUtils.zipStream()
            val bytes = GameOutputStream().apply {
                writeInt(0)
                writeString("Hi")
            }.getByteArray()

            zip.addCompressBytes("bytes", bytes)
            zip.addCompressBytes("inStream-HPS", DisableSyncByteArrayInputStream(bytes))
            zip.addCompressBytes("inStream-Java", ByteArrayInputStream(bytes))
            zip.addCompressBytes("/bytes/bytes", bytes)
            zip.addCompressBytes("/bytes/inStream-HPS", DisableSyncByteArrayInputStream(bytes))
            zip.addCompressBytes("/bytes/inStream-Java", ByteArrayInputStream(bytes))

            val zipNew = CompressionEncoderUtils.zipStream()
            zipNew.addCompressBytes("bytes_New", bytes)
            zipNew.addCompressBytes("inStream-HPS_New", DisableSyncByteArrayInputStream(bytes))
            zipNew.addCompressBytes("inStream-Java_New", ByteArrayInputStream(bytes))
            zipNew.addCompressBytes("/bytes/bytes", bytes)
            zipNew.addCompressBytes("/bytes/inStream-HPS", DisableSyncByteArrayInputStream(bytes))
            zipNew.addCompressBytes("/bytes/inStream-Java", ByteArrayInputStream(bytes))

            val unzip = CompressionDecoderUtils.zipAllReadStream(DisableSyncByteArrayInputStream(zipNew.flash()))

            zip.addCompressBytes("zip_New", unzip)

//            val file = FileUtil.getFile("CompressionEncoderUtilsTest.zip")
//            file.writeFileByte(zip.flash())
        }
    }

    @Test
    fun sevenStream() {
        ExtractUtils.tryRunTest {
            val seven = CompressionEncoderUtils.sevenStream()
            val bytes = GameOutputStream().apply {
                writeInt(0)
                writeString("Hi")
            }.getByteArray()

            seven.addCompressBytes("bytes", bytes)
            seven.addCompressBytes("inStream-HPS", DisableSyncByteArrayInputStream(bytes))
            seven.addCompressBytes("inStream-Java", ByteArrayInputStream(bytes))
            seven.addCompressBytes("/bytes/bytes", bytes)
            seven.addCompressBytes("/bytes/inStream-HPS", DisableSyncByteArrayInputStream(bytes))
            seven.addCompressBytes("/bytes/inStream-Java", ByteArrayInputStream(bytes))

            val sevenNew = CompressionEncoderUtils.sevenStream()
            sevenNew.addCompressBytes("bytes_New", bytes)
            sevenNew.addCompressBytes("inStream-HPS_New", DisableSyncByteArrayInputStream(bytes))
            sevenNew.addCompressBytes("inStream-Java_New", ByteArrayInputStream(bytes))
            sevenNew.addCompressBytes("/bytes/bytes", bytes)
            sevenNew.addCompressBytes("/bytes/inStream-HPS", DisableSyncByteArrayInputStream(bytes))
            sevenNew.addCompressBytes("/bytes/inStream-Java", ByteArrayInputStream(bytes))

            val unzip = CompressionDecoderUtils.sevenAllReadStream(DisableSyncByteArrayInputStream(sevenNew.flash()))

            seven.addCompressBytes("7z_New", unzip)

//            val file = FileUtil.getFile("CompressionEncoderUtilsTest.7z")
//            file.writeFileByte(seven.flash())
        }
    }
}