/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.file

import net.rwhps.server.data.global.Data
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.struct.map.OrderedMap
import net.rwhps.server.util.IsUtils
import net.rwhps.server.util.SystemUtils
import net.rwhps.server.util.algorithms.digest.DigestUtils
import net.rwhps.server.util.compression.CompressionDecoderUtils
import net.rwhps.server.util.compression.core.AbstractDecoder
import net.rwhps.server.util.io.IoOutConversion.fileToOutStream
import net.rwhps.server.util.io.IoOutConversion.fileToStream
import net.rwhps.server.util.io.IoRead.readFileToByteArray
import net.rwhps.server.util.io.IoReadConversion.fileToReadStream
import net.rwhps.server.util.io.IoReadConversion.fileToStream
import net.rwhps.server.util.log.Log.error
import java.io.*
import java.net.URLDecoder.decode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Server文件处理核心
 *
 * ## 推荐教程:
 * ### 误区
 * FileUtils() 是指实例 FileUtils, `FileUtils.` 是指静态方法
 *
 * ### 快捷使用
 * - FileUtils.getFile("文件名") 因为不会创建文件 同时 位置和Jar同目录
 * - FileUtils.getFolder("文件夹名") 因为不会创建文件 只会创建目录
 * - FileUtils.getFolder("文件夹名").toFile("文件名") 只会创建目录
 *
 * #### 扩展
 * FileUtils.getFolder("文件夹名" , true)返回的 FileUtil 在您操作 toFile toFolder 时 会直接返回新的 FileUtil 对象, 而原来的对象可重用
 *
 * ## 注意:
 * FileUtils.toFolder 初始目录是Server.jar的目录或者Main提交的参数目录
 * - toFolder 只是起一个进入作用
 * - FileUtil().mkdir() 会创建文件夹并尝试创建文件
 * - FileUtil().createNewFile() 会尝试创建文件
 * - FileUtil(filepath,true).toFile() 会直接返回一个新的 FileUtil
 * - FileUtil(filepath,true).toFolder() 会直接返回一个新的 FileUtil
 *
 * #### FileUtils 的三个实例不会做任何事情，也不会创建目录和文件:
 * - 如果需要先目录再文件 那么用FileUtils.toFolder(文件夹名).toFile(文件名)
 * - 如果需要先进入多个目录 那么用FileUtils.toFolder(文件夹名).toFolder(文件夹名)
 * - 只有使用FileUtil().read/Write时才会进行文件创建
 *
 * ## 部分误区:
 * 在操作FileUtils()的时候不会进行创建文件,但是当你操作File的时候,那么就会创建文件
 *
 * 例子:
 * - FileUtils.getFile("文件名").exists()的时候就不会创建文件
 * - FileUtils.getFile("文件名").getInputsStream()的时候就会自动创建一个文件
 *
 * 只有FileUtils.getFolder("文件夹名" , true) 创建的 和 FileUtil(filepath,true) 创建的 FileUtil 在您操作 toFile toFolder 时 会直接返回新的 FileUtil 对象
 *
 * 欢迎提交修改
 *
 * @author Dr (dr@der.kim)
 */
open class FileUtils {
    /** 内部的File  */
    var file: File
        protected set

    /** 当前操作的文件地址  */
    var path: String
        protected set

    protected val isNewFile: Boolean

    @JvmOverloads
    constructor(file: File, isNewFile: Boolean = false) {
        this.file = file
        this.path = file.path
        this.isNewFile = isNewFile
    }

    constructor(filepath: String, isNewFile: Boolean = false) {
        this.path = filepath
        file = File(filepath)
        this.isNewFile = isNewFile

    }

    protected constructor(file: File, filepath: String, ismkdir: Boolean = false, isNewFile: Boolean = false) {
        this.file = file
        this.path = filepath
        if (ismkdir) {
            file.mkdirs()
        }
        this.isNewFile = isNewFile
    }

    val name: String get() = file.name

    open fun exists(): Boolean = file.exists()

    open fun notExists(): Boolean = !file.exists()

    fun delete(): Boolean {
        return try {
            FileOperation.recursiveDelete(file)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun length(): Long = file.length()

    fun toFile(filename: String): FileUtils {
        var fileUtil = this
        var fileName = filename

        if (filename.contains("/")) {
            fileName = filename.split("/").toTypedArray()[filename.split("/").toTypedArray().size - 1]
            fileUtil = fileUtil.toFolder(filename.replace(fileName, ""))
        }

        return if (isNewFile) {
            FileUtils(File(fileUtil.path + "/" + fileName), isNewFile)
        } else {
            file = File(fileUtil.path + "/" + fileName)
            path = this.path + "/" + filename
            this
        }
    }

    fun toFolder(filename: String): FileUtils {
        if (filename == "") {
            return this
        }
        val to = cehckFolderPath(this.path, filename)

        return if (isNewFile) {
            FileUtils(File(to), to, true, isNewFile)
        } else {
            this.file = File(to)
            this.path = to
            file.mkdirs()
            this
        }
    }

    val fileList: Seq<File>
        get() {
            val array = file.listFiles()
            val fileList = Seq<File>()
            if (array == null || array.isEmpty()) {
                return fileList
            }
            for (value in array) {
                if (!value.isDirectory && value.isFile) {
                    fileList.add(value)
                }
            }
            return fileList
        }

    val filePollingList: OrderedMap<String, File>
        get() {
            val array = file.listFiles()
            val fileList = OrderedMap<String, File>()
            if (IsUtils.isBlank(array)) {
                return fileList
            }
            for (value in array!!) {
                if (!value.isDirectory) {
                    if (value.isFile) {
                        fileList[value.name] = value
                    }
                } else {
                    FileUtils(value).filePollingList.eachAll { k, v ->
                        fileList["${value.name}/${k}"] = v
                    }
                }
            }
            return fileList
        }

    val fileListNotNullSize: Seq<File>
        get() {
            val list = Seq<File>()
            fileList.eachAllFind({ e: File -> e.length() > 0 }) { value: File -> list.add(value) }
            return list
        }

    val fileListNotNullSizeSort: Seq<File>
        get() {
            val list: Seq<File> = fileListNotNullSize
            list.sortWith { o1, o2 -> o1.name.compareTo(o2.name) }
            return list
        }

    val zipDecoder: AbstractDecoder
        get() = CompressionDecoderUtils.zip(file)

    val md5: String
        get() = DigestUtils.md5Hex(getInputsStream())

    fun setReadOnly(): Boolean = file.setReadOnly()

    fun setPosixFilePermissions(permission: Array<PosixFilePermission>) {
        Files.setPosixFilePermissions(file.toPath(), permission.toSet())
    }

    /**
     *
     * @param log Log
     * @param tail 是否尾部写入
     */
    open fun writeFile(log: Any, tail: Boolean = false) {
        mkdir()
        try {
            fileToOutStream(file, tail).use { osw ->
                osw.write(log.toString())
                osw.flush()
            }
        } catch (e: Exception) {
            error("writeFile", e)
        }
    }

    open fun writeFileByte(bytes: ByteArray, tail: Boolean = false) {
        mkdir()
        try {
            BufferedOutputStream(FileOutputStream(file, tail)).use { osw ->
                osw.write(bytes)
                osw.flush()
            }
        } catch (e: Exception) {
            error("writeByteFile", e)
        }
    }

    @Throws(Exception::class)
    open fun writeByteOutputStream(tail: Boolean = false): FileOutputStream {
        mkdir()
        return fileToStream(file, tail)
    }

    @Throws(IOException::class)
    fun getInputsStream(): FileInputStream {
        mkdir()
        return fileToStream(file)
    }

    @Throws(IOException::class)
    fun readInputsStream(): InputStreamReader {
        mkdir()
        return fileToReadStream(file)
    }

    @Throws(IOException::class)
    fun readFileByte(): ByteArray {
        mkdir()
        return readFileToByteArray(file)
    }

    open fun readFileStringData(): String {
        mkdir()
        try {
            FileInputStream(file).use { fileInputStream -> return readFileString(fileInputStream) }
        } catch (fileNotFoundException: FileNotFoundException) {
            error("FileNotFoundException", fileNotFoundException)
        } catch (ioException: IOException) {
            error("Read IO Error", ioException)
        }
        return ""
    }

    open fun readFileListStringData(): Seq<String> {
        mkdir()
        try {
            FileInputStream(file).use { fileInputStream -> return readFileListString(fileInputStream) }
        } catch (fileNotFoundException: FileNotFoundException) {
            error("FileNotFoundException")
        } catch (ioException: IOException) {
            error("Read IO Error", ioException)
        }
        return Seq()
    }

    fun copy(newFile: FileUtils) {
        FileOperation.copyFile(file, newFile.file)
    }

    fun mkdir() {
        file.parentFile?.mkdirs()

        if (file.isDirectory) {
            return
        }

        if (!file.exists()) {
            try {
                file.createNewFile()
            } catch (e: IOException) {
                error("Mk file", e)
            }
        }
    }

    fun createNewFile() {
        if (file.isDirectory) {
            return
        }

        if (!file.exists()) {
            try {
                file.createNewFile()
            } catch (e: IOException) {
                error("Mk file", e)
            }
        }
    }

    companion object {
        /**
         * 默认的地址前缀
         * 如果不为null将会直接使用path而不是使用jar的位置
         */
        private var defaultFilePath: String = initPath()

        /**
         * 加载默认的文件路径
         * 默认使用Jar同目录下的 与启动的位置无关
         */
        private fun initPath(): String {
            val path = FileUtils::class.java.protectionDomain.codeSource.location.path
            val pathSplit = path.split("/").toTypedArray()
            val jarName = pathSplit[pathSplit.size - 1]
            val jarPath = path.replace(jarName, "")

            return pathProcess(decode(jarPath, StandardCharsets.UTF_8))
        }

        /**
         * 设置Jar的数据存储位置
         * @param customFilePath String
         */
        @JvmStatic
        fun setFilePath(customFilePathIn: String) {
            defaultFilePath = pathProcess(customFilePathIn)
        }

        /**
         * 进入目录
         * @param toFile String?
         * @return FileUtil
         */
        @JvmStatic
        @JvmOverloads
        fun getFolder(toFile: String? = null, isNewFile: Boolean = false): FileUtils {
            val to = if (null != toFile) {
                cehckFolderPath(defaultFilePath, toFile)
            } else {
                defaultFilePath
            }
            return FileUtils(File(to), to, true, isNewFile)
        }

        @JvmStatic
        fun getFile(toFile: String): FileUtils {
            return FileUtils(File(defaultFilePath + toFile))
        }

        @JvmStatic
        fun getTempFile(prefix: String): FileUtils {
            return getFolder(Data.ServerCachePath).toFile(prefix)
        }

        @JvmStatic
        fun getTempDirectory(prefix: String): FileUtils {
            return getFolder(Data.ServerCachePath).toFolder(prefix)
        }

        @JvmStatic
        fun readFileString(inputStream: InputStream): String {
            return FileStream.readFileString(inputStream)
        }

        @JvmStatic
        fun readFileListString(inputStream: InputStream): Seq<String> {
            return FileStream.readFileListString(inputStream)
        }

        @JvmStatic
        fun getInternalFileStream(name: String): InputStream {
            return FileUtils::class.java.getResourceAsStream(name) ?: throw FileNotFoundException("The file could not be found: $name")
        }

        @JvmStatic
        fun getPath(name: String): String {
            return cehckFolderPath(defaultFilePath, name)
        }

        @JvmStatic
        fun splicePath(splice1: String, splice2: String): String {
            return cehckFolderPath(splice1, splice2)
        }

        @JvmStatic
        fun getMyFilePath(): String {
            return decode(this::class.java.protectionDomain.codeSource.location.path, Data.UTF_8)
        }

        /**
         * 无解
         * @return InputStream
         */
        @JvmStatic
        fun getMyCoreJarStream(): InputStream {
            return FileUtils(getMyFilePath()).getInputsStream()
        }

        private fun pathProcess(path: String): String {
            var cache = path
            // Windows 不允许文件夹存在 :
            // 同时 获取的位置前面会有 /
            // 例如 /A:/a/a.jar
            if (SystemUtils.isWindows &&
                cache.length >= 3 &&
                (cache[0] == '/' || cache[0] == '\\') &&
                cache[2] == ':'
            ) {
                cache = cache.substring(1)
            }
            val customFilePath = cache.replace("\\", "/")
            return if (customFilePath.endsWith("/")) {
                customFilePath
            } else {
                "$customFilePath/"
            }
        }

        private fun cehckFolderPath(defPath: String, path: String): String {
            if (path == "") {
                return defPath
            }

            var to = if ("/" == path[0].toString()) {
                path.substring(1)
            } else {
                path
            }
            // 过滤末尾 /
            to = if (to.endsWith("/")) {
                to.substring(0, to.length - 1)
            } else {
                to
            }

            return if (defPath.endsWith("/")) {
                defPath + to
            } else {
                "$defPath/$to"
            }
        }
    }
}
