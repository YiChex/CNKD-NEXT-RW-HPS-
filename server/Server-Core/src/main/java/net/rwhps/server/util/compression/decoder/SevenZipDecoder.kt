/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.compression.decoder

import net.rwhps.server.func.Control
import net.rwhps.server.util.compression.core.AbstractDecoder
import net.rwhps.server.util.compression.core.AbstractDecoderImpl
import net.rwhps.server.util.io.IoRead
import net.rwhps.server.util.log.Log
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 7z
 * @property sevenZipFile SevenZFile
 * @author Dr (dr@der.kim)
 */
internal open class SevenZipDecoder: AbstractDecoder {
    constructor(bytes: ByteArray): super(SevenDecoderImpl(SevenZFile(SeekableInMemoryByteChannel(bytes))))

    constructor(file: File): super(SevenDecoderImpl(SevenZFile(file)))

    private class SevenDecoderImpl(
        private val sevenZipFile: SevenZFile
    ): AbstractDecoderImpl<SevenZArchiveEntry> {
        override fun eachAll(run: (SevenZArchiveEntry, ()->InputStream, IoRead.MultiplexingReadStream) -> Control.ControlFind) {
            try {
                var sevenZipEntry: SevenZArchiveEntry?
                IoRead.MultiplexingReadStream().use { multiplexingReadStream ->
                    while (sevenZipFile.nextEntry.also { sevenZipEntry = it } != null) {
                        if (!sevenZipEntry!!.isDirectory) {
                            if (run(sevenZipEntry!!, { sevenZipFile.getInputStream(sevenZipEntry) }, multiplexingReadStream) == Control.ControlFind.BREAK) {
                                return
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                Log.error(e)
            }
        }

        override fun close() {
            sevenZipFile.close()
        }
    }
}