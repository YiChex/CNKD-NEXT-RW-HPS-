/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.compression.core

import net.rwhps.server.func.Control
import net.rwhps.server.game.GameMaps
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.struct.map.OrderedMap
import net.rwhps.server.util.file.FileName
import net.rwhps.server.util.io.IoRead
import net.rwhps.server.util.log.exp.FileException
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 解压 通用接口
 * @author Dr (dr@der.kim)
 */
open class AbstractDecoder(
    private val decoder: AbstractDecoderImpl<*>
): Closeable {
    fun setPhysicalOrder(value: Boolean) {
        decoder.setPhysicalOrder(value)
    }

    /**
     * 获取ZIP内的指定后辍的文件名(无后辍)与bytes
     * @param endWith String
     * @return OrderedMap<String, ByteArray>
     */
    fun getSpecifiedSuffixInThePackage(endWith: String, withSuffix: Boolean = false): OrderedMap<String, ByteArray> {
        val result = OrderedMap<String, ByteArray>(8)

        decoder.eachAll { entry, inputStream, multiplexingReadStream ->
            var name = FileName.getFileName(entry.name)
            if (name.endsWith(endWith)) {
                if (!withSuffix) {
                    name = FileName.getFileNameNoSuffix(name)
                }
                result[name] = multiplexingReadStream.readInputStreamBytes(inputStream())
            }
            return@eachAll Control.ControlFind.CONTINUE
        }

        return result
    }

    /**
     * 获取ZIP内的指定后辍的文件名(全名+路径)与bytes
     * @return OrderedMap<String, ByteArray>
     */
    fun getSpecifiedSuffixInThePackageAllFileNameAndPath(endWithSeq: Seq<String>): OrderedMap<String, ByteArray> {
        val result = OrderedMap<String, ByteArray>(8)

        decoder.eachAll { entry, inputStream, multiplexingReadStream ->
            val nameCache = entry.name
            endWithSeq.eachAllFind({ nameCache.endsWith(it) }) { _: String ->
                result[nameCache] = multiplexingReadStream.readInputStreamBytes(inputStream())
            }
            return@eachAll Control.ControlFind.CONTINUE
        }

        return result
    }

    /**
     * 获取ZIP内满足后辍的文件名(无后辍)
     * @param endWith String
     * @return Seq<String>
     */
    fun getTheFileNameOfTheSpecifiedSuffixInTheZip(endWith: String): Seq<String> {
        val result = Seq<String>(8)

        decoder.eachAll { entry, _, _ ->
            val name = entry.name
            if (name.endsWith(endWith)) {
                result.add(name.substring(0, name.length - name.substring(name.lastIndexOf(".")).length))
            }
            return@eachAll Control.ControlFind.CONTINUE
        }

        return result
    }

    fun getTheFileNamesOfTheSpecifiedSuffixInTheZipIgnoreCase(endWith: String, withSuffix: Boolean = false): Seq<String> {
        val result = Seq<String>(8)
        val normalizedSuffix = if (endWith.startsWith(".")) endWith else ".$endWith"

        decoder.eachAll { entry, _, _ ->
            val name = entry.name
            if (name.endsWith(normalizedSuffix, ignoreCase = true)) {
                result.add(if (withSuffix) name else name.substring(0, name.length - normalizedSuffix.length))
            }
            return@eachAll Control.ControlFind.CONTINUE
        }

        return result
    }

    fun getFilesByFileNameIgnoreCase(fileName: String): OrderedMap<String, ByteArray> {
        val result = OrderedMap<String, ByteArray>(8)

        decoder.eachAll { entry, inputStream, multiplexingReadStream ->
            if (FileName.getFileName(entry.name).equals(fileName, ignoreCase = true)) {
                result[entry.name] = multiplexingReadStream.readInputStreamBytes(inputStream())
            }
            return@eachAll Control.ControlFind.CONTINUE
        }

        return result
    }

    /**
     * 获取地图文件字节
     * @param mapData MapData
     * @return ByteArray
     */
    @Throws(Exception::class)
    fun getTheFileBytesOfTheSpecifiedSuffixInTheZip(mapData: GameMaps.MapData): ByteArray {
        var result: ByteArray? = null

        decoder.eachAll { entry, inputStream, _ ->
            val name = entry.name
            if (name.endsWith(mapData.mapType.fileType) && FileName.getFileNameNoSuffix(name).contains(mapData.mapFileName)) {
                result = IoRead.readInputStreamBytes(inputStream())
                return@eachAll Control.ControlFind.BREAK
            }
            return@eachAll Control.ControlFind.CONTINUE
        }

        return result ?: throw FileException("CANNOT_FIND_FILE")
    }

    /**
     * 获取 ZIP 内指定文件名的字节流
     * @param nameIn String
     * @return InputStream?
     */
    fun getZipNameBytes(nameIn: String, ignoreCase: Boolean = false): ByteArray? {
        var result: ByteArray? = null

        decoder.eachAll { entry, inputStream, _ ->
            if (entry.name.equals(nameIn, ignoreCase = ignoreCase)) {
                result = IoRead.readInputStreamBytes(inputStream())
                return@eachAll Control.ControlFind.BREAK
            }
            return@eachAll Control.ControlFind.CONTINUE
        }

        return result
    }

    fun getZipNameInputStream(nameIn: String): InputStream? {
        var result: InputStream? = null

        decoder.eachAll { entry, inputStream, _ ->
            if (entry.name == nameIn) {
                result = inputStream()
                return@eachAll Control.ControlFind.BREAK
            }
            return@eachAll Control.ControlFind.CONTINUE
        }

        return result
    }

    /**
     * 获取 ZIP 全部数据
     * @return OrderedMap<String, ByteArray>
     */
    fun extractEntriesUnderPrefix(
        prefixIn: String,
        targetFolder: File,
        excludeEntries: Set<String> = emptySet(),
        allowedSuffixes: Set<String> = emptySet()
    ): Int {
        val normalizedPrefix = prefixIn.replace("\\", "/").trimStart('/')
        val normalizedExcludes = excludeEntries.map { it.replace("\\", "/").trimStart('/') }.toSet()
        val normalizedSuffixes = allowedSuffixes.map { suffix ->
            if (suffix.startsWith(".")) suffix else ".$suffix"
        }
        val canonicalTargetFolder = targetFolder.canonicalFile
        var extracted = 0

        canonicalTargetFolder.mkdirs()
        decoder.eachAll { entry, inputStream, _ ->
            val entryName = entry.name.replace("\\", "/").trimStart('/')
            if (normalizedPrefix.isNotBlank() && !entryName.startsWith(normalizedPrefix, ignoreCase = true)) {
                return@eachAll Control.ControlFind.CONTINUE
            }
            if (normalizedExcludes.any { entryName.equals(it, ignoreCase = true) }) {
                return@eachAll Control.ControlFind.CONTINUE
            }
            if (normalizedSuffixes.isNotEmpty() && normalizedSuffixes.none { entryName.endsWith(it, ignoreCase = true) }) {
                return@eachAll Control.ControlFind.CONTINUE
            }

            val relativeName = if (normalizedPrefix.isBlank()) {
                entryName.substringAfterLast("/")
            } else {
                entryName.substring(normalizedPrefix.length).trimStart('/')
            }
            if (relativeName.isBlank() || relativeName.contains("..")) {
                return@eachAll Control.ControlFind.CONTINUE
            }

            val outputFile = File(canonicalTargetFolder, relativeName).canonicalFile
            if (!outputFile.path.startsWith(canonicalTargetFolder.path + File.separator)) {
                return@eachAll Control.ControlFind.CONTINUE
            }
            outputFile.parentFile?.mkdirs()
            inputStream().use { input ->
                BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
                    input.copyTo(output)
                }
            }
            extracted++
            return@eachAll Control.ControlFind.CONTINUE
        }

        return extracted
    }

    fun getZipAllBytes(withPath: Boolean = true): OrderedMap<String, ByteArray> {
        val result = OrderedMap<String, ByteArray>(8)

        decoder.eachAll { entry, inputStream, multiplexingReadStream ->
            val nameCache = entry.name
            val name = if (withPath) {
                nameCache
            } else {
                FileName.getFileName(entry.name)
            }

            val bytes = multiplexingReadStream.readInputStreamBytes(inputStream())
            result[name] = bytes

            return@eachAll Control.ControlFind.CONTINUE
        }

        return result
    }

    /**
     * 关闭 Close
     */
    override fun close() {
        decoder.close()
    }
}
