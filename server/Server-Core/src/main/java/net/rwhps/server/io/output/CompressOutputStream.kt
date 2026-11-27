/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.io.output

import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.util.Time
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.util.*
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipOutputStream

/**
 * 提供压缩流支持
 *
 * @author Dr (dr@der.kim)
 */
class CompressOutputStream(val head: String, outputStream: DisableSyncByteArrayOutputStream): GameOutputStream(outputStream) {
    companion object {
        fun getGzipOutputStream(head: String, isGzip: Boolean): CompressOutputStream {
            return CompressOutputStream(head, if (isGzip) {
                object: DisableSyncByteArrayOutputStream() {
                    override fun toByteArray(): ByteArray {
                        val out = DisableSyncByteArrayOutputStream()
                        val gzip = GZIPOutputStream(out)
                        gzip.write(super.toByteArray())
                        gzip.close()
                        return out.toByteArray()
                    }
                }
            } else {
                DisableSyncByteArrayOutputStream()
            })
        }

        fun getZipOutputStream(head: String, isZip: Boolean): CompressOutputStream {
            return CompressOutputStream(head, if (isZip) {
                object: DisableSyncByteArrayOutputStream() {
                    override fun toByteArray(): ByteArray {
                        val out = DisableSyncByteArrayOutputStream()
                        ZipOutputStream(out).use { zipOneFileOut ->
                            val oze = ZipArchiveEntry("file")
                            zipOneFileOut.putNextEntry(oze)
                            zipOneFileOut.write(super.toByteArray())
                            zipOneFileOut.closeEntry()
                            zipOneFileOut.flush()
                        }
                        return out.toByteArray()
                    }
                }
            } else {
                DisableSyncByteArrayOutputStream()
            })
        }

        fun get7zOutputStream(head: String, is7z: Boolean): CompressOutputStream {
            return CompressOutputStream(head, if (is7z) {
                object: DisableSyncByteArrayOutputStream() {
                    override fun toByteArray(): ByteArray {
                        val out = SeekableInMemoryByteChannel()
                        SevenZOutputFile(out).use { sevenOneFileOut ->
                            val oze = SevenZArchiveEntry().apply {
                                name = "file"
                                // 文件最后修改时间
                                accessDate = Date(Time.concurrentMillis())
                            }
                            sevenOneFileOut.putArchiveEntry(oze)
                            sevenOneFileOut.write(super.toByteArray())
                            sevenOneFileOut.closeArchiveEntry()
                            sevenOneFileOut.finish()
                        }
                        return out.array()
                    }
                }
            } else {
                DisableSyncByteArrayOutputStream()
            })
        }
    }
}