/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.dependent

import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.global.Data
import net.rwhps.server.func.Control.ControlFind
import net.rwhps.server.io.input.DisableSyncByteArrayInputStream
import net.rwhps.server.net.manage.DownloadManage
import net.rwhps.server.net.manage.HttpRequestManage
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.util.algorithms.digest.DigestUtils
import net.rwhps.server.util.compression.CompressionAlgorithm
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.file.plugin.PluginData
import net.rwhps.server.util.file.plugin.serializer.MachineFriendlySerializers
import net.rwhps.server.util.file.plugin.serializer.SerializersEnum
import net.rwhps.server.util.file.plugin.value
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.log.exp.LibraryManagerError
import okhttp3.OkHttpClient
import org.w3c.dom.Node
import java.io.File
import java.lang.reflect.Method
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

/**
 * 热加载依赖
 *
 * @author Dr (dr@der.kim)
 */
class LibraryManager(china: Boolean = Data.serverCountry == "CN"): AgentAttachData() {
    private val source = Seq<String>()

    init {
        if (china) {
            source.add(UrlData["MavenAli"]!!)
            source.add(UrlData["MavenTencent"]!!)
            //source.add(UrlData["MavenNetease"]!!)
            source.add(UrlData["MavenHuaWei"]!!)
            source.add(UrlData["Maven"]!!)
            source.add(UrlData["JitPack"]!!)
        } else {
            source.add(UrlData["Maven"]!!)
            source.add(UrlData["JitPack"]!!)
        }
    }

    fun addSource(source: String) {
        this.source.add(source)
    }

    /**
     * 导入本地的依赖
     *
     * @param file FileUtil
     */
    fun customImportLib(file: File) {
        dependenciesFile.add(file)
    }

    /**
     * 导入本地的依赖
     *
     * @param file FileUtil
     */
    fun customImportLib(file: FileUtils) {
        dependenciesFile.add(file.file)
    }

    /**
     * 按照 Gradle DSL 的语法导入
     *
     * @param text                  String
     * @param block                 Function
     * @throws LibraryManagerError  仓库找不到这个依赖
     */
    @Throws(LibraryManagerError.DependencyNotFoundException::class)
    @JvmOverloads
    fun implementation(text: String, block: (LibraryManager.() -> Unit)? = null) {
        block?.run { this() }
        importLib0(text)
    }

    /**
     * 按照挨个设置导入
     *
     * @param group                 组
     * @param module                模块
     * @param version               版本
     * @param block                 Function
     * @throws LibraryManagerError  仓库找不到这个依赖
     */
    @Throws(LibraryManagerError.DependencyNotFoundException::class)
    @JvmOverloads
    fun implementation(
        group: String, module: String, version: String, classifier: String = "", block: (LibraryManager.() -> Unit)? = null
    ) {
        block?.run { this() }
        importLib0(group, module, version, classifier)
    }

    /**
     * 排除指定依赖
     *
     * @param group                 组
     * @param module                模块
     */
    fun exclude(group: String, module: String, version: String, classifier: String) {
        val import = ImportGroupData(group, module, version, classifier)
        if (!tempGroup.contains(import)) {
            tempGroup.add(import)
        }
    }

    /**
     * 加载依赖到 JVM
     */
    fun loadToClassLoader() {
        Log.clog(Data.i18NBundle.getinput("server.load.jar"))
        load()
        val loader = getClassLoader()
        load.eachAll {
            if (!loadEnd.contains(it)) {
                if (loadLibrarySafely(it, loader)) {
                    loadEnd.add(it)
                }
            }
        }
        dependenciesFile.eachAll {
            if (!loadEnd.contains(it)) {
                if (loadLibrarySafely(it, loader)) {
                    loadEnd.add(it)
                }
            }
        }
    }

    private fun loadLibrarySafely(file: File, loader: (File) -> Boolean): Boolean {
        if (!file.exists()) {
            Log.warn("[LibraryManager] Missing library, skip", file.absolutePath)
            return false
        }
        if (file.length() <= 0L) {
            // P3-11: a 0-byte jar is a truncated/failed download, never a usable library.
            // Skipping it used to let startup continue and fail much later with a confusing
            // NoClassDefFoundError. Fail loudly at the point of detection instead.
            //
            // This is deliberately NOT applied to the missing-file case above: an absent
            // optional library is a legitimate state, whereas an empty one never is.
            Log.fatal("[LibraryManager] Empty (0-byte) library, refusing to start", file.absolutePath)
            throw LibraryManagerError("[LibraryManager] Empty (0-byte) library: ${file.absolutePath}")
        }
        return loader(file)
    }

    /**
     * 下载依赖
     */
    private fun load() {
        dependenciesDown.eachAll {
            val fileUtils = FileUtils.getFolder(Data.ServerLibPath).toFile(it.fileName)
            if (!fileUtils.exists()) {
                Log.track("Down Jar", it.getDownUrl())
                DownloadManage.addDownloadTask(
                    DownloadManage.DownloadData(it.getDownUrl(), fileUtils, digest = httpManager.doGet(it.getJarVerifyHash()), progressFlag = true)
                ).also { success ->
                    if (success) {
                        load.add(fileUtils.file)
                    } else {
                        Log.fatal("Download Failed ${fileUtils.name}")
                    }
                }
            } else {
                load.add(fileUtils.file)
            }
        }
    }

    /**
     * 获取一个加载器URLClassLoader
     */
    private fun getClassLoader(): (File) -> Boolean {
        return if (ClassLoader.getSystemClassLoader() is URLClassLoader) {
            val f: Method = URLClassLoader::class.java.getDeclaredMethod("addURL", URL::class.java)
            f.isAccessible = true

            { file: File ->
                try {
                    f.invoke(ClassLoader.getSystemClassLoader(), file.toURI().toURL())
                    Log.debug("Load Lib Jar", file.name)
                    true
                } catch (classLoad: Exception) {
                    Log.fatal("Jar 1.8 Load", classLoad)
                    false
                }
            }
        } else {
            { file: File ->
                try {
                    instrumentation.appendToSystemClassLoaderSearch(JarFile(file))
                    Log.debug("Load Lib Jar", file.name)
                    true
                } catch (classLoad: Exception) {
                    Log.fatal("Jar 1.8+ Load : (${file.name})", classLoad)
                    false
                }
            }
        }
    }

    @Throws(LibraryManagerError.DependencyNotFoundException::class)
    private fun importLib0(text: String, down: Boolean = true) {
        val array = text.split(":")
        if (array.size == 4) {
            importLib0(array[0], array[1], array[2], array[3], down)
        } else {
            importLib0(array[0], array[1], array[2], "", down = down)
        }
    }

    @Throws(LibraryManagerError.DependencyNotFoundException::class)
    private fun importLib0(group: String, module: String, version: String, classifier: String, down: Boolean = true) {
        val groupArray = group.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val constructSource = StringBuilder("/")
        for (s in groupArray) {
            constructSource.append(s).append("/")
        }
        constructSource.append(module).append("/").append(version).append("/").append(module).append("-").append(version)

        val saveFileName = if (classifier.isBlank()) "$module-$version.jar" else "$module-$version-$classifier.jar"
        val groupLibData = ImportGroupData(group, module, version, classifier)
        if (tempGroup.contains(groupLibData)) {
            return
        }
        tempGroup.add(groupLibData)
        getDepend(groupLibData, ImportData(constructSource.toString(), classifier, saveFileName), down)
    }

    /**
     * 解析 POM 依赖
     *
     * @param importData ImportData
     * @param down Boolean
     * @throws LibraryManagerError
     *
     * @author Dr (dr@der.kim)
     * @author way-zer
     */
    @Throws(LibraryManagerError.DependencyNotFoundException::class)
    private fun getDepend(groupLibData: ImportGroupData, importData: ImportData, down: Boolean) {
        source.eachControlAll { pomUrl ->
            val pomCacheResult = pomCache[groupLibData.toString()] ?: httpManager.doGet(importData.getDownPom(pomUrl)).let {
                if (it.isBlank()) {
                    return@let ""
                }

                if (!DigestUtils.md5Hex(it).equals(httpManager.doGet(importData.getPomVerifyHash(pomUrl)), ignoreCase = true)) {
                    Log.warn("[Pom Verify Hash] Does not match", importData.getDownPom(pomUrl))
                    return@let ""
                } else {
                    // Limit pomCache size to prevent unbounded growth on disk and in memory
                    if (pomCache.size >= POM_CACHE_MAX_SIZE) {
                        pomCache.clear()
                    }
                    pomCache[groupLibData.toString()] = it
                    return@let it
                }
            }

            if (pomCacheResult.isNotBlank() && pomCacheResult.contains("project", true)) {
                // Set a valid source
                importData.mainSource = pomUrl
                try {
                    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                        isIgnoringComments = true
                        isIgnoringElementContentWhitespace = true
                    }.newDocumentBuilder().parse(DisableSyncByteArrayInputStream(pomCacheResult.toByteArray()))

                    val root = doc.firstChild.also {
                        if (it.nodeName != "project") {
                            Log.error("[resolve Pom] Error Pom: $pomUrl")
                            return@eachControlAll ControlFind.CONTINUE
                        }
                    }.toMap()

                    val versions = mutableMapOf<String, String>()

                    //Fix variable in version like javaLin
                    root["properties"]?.toSeq()?.forEach {
                        if (it.nodeName.endsWith(".version")) {
                            versions[it.nodeName] = it.textContent
                        }
                    }

                    //Fix use {project.version} like jetty
                    root["parent"]?.toMap()?.get("version")?.let {
                        versions["project.version"] = it.textContent
                    }

                    root["dependencies"]?.toSeq()?.forEach { dNode ->
                        if (dNode.nodeName != "dependency") {
                            return@forEach
                        }

                        val d = dNode.toMap()
                        val scope = d["scope"]?.textContent ?: "compile"

                        if (scope != "compile" && scope != "runtime") {
                            return@forEach
                        }
                        if (d["optional"]?.textContent == "true") {
                            return@forEach
                        }
                        val group = d["groupId"]?.textContent ?: let {
                            Log.error("[resolve Pom] Can't get 'groupId' from ${dNode.textContent}")
                            return@forEach
                        }
                        val name = d["artifactId"]?.textContent ?: let {
                            Log.error("[resolve Pom] Can't get 'name' from ${dNode.textContent}")
                            return@forEach
                        }
                        // Netty 的阴间 POM, 包含两个 dependencies
                        // 一个有版本, 一个无版本
                        var version = d["version"]?.textContent ?: let {
                            return@forEach
                        }
                        if (version.startsWith("\${")) version = versions[version.substring(2, version.length - 1)] ?: let {
                            Log.error("[resolve Pom] Can't resolve version because $version can't find")
                            return@forEach
                        }

                        val versionArray = version.split("-")
                        if (versionArray.size > 1) {
                            importLib0(group, name, versionArray[0], versionArray[1], down = down)
                        } else {
                            importLib0(group, name, version, "", down = down)
                        }
                    }
                } catch (e: Exception) {
                    Log.error("[POM parsing error]", e)
                }
                if (down) {
                    if (!dependenciesDown.contains(importData)) {
                        dependenciesDown.add(importData)
                    }
                }
                return@eachControlAll ControlFind.BREAK
            }
            return@eachControlAll ControlFind.CONTINUE
        }
    }

    private class ImportGroupData(val group: String, val module: String, val version: String, val classifier: String) {
        override fun equals(other: Any?): Boolean {
            if (other is ImportGroupData) {
                return other.group == group && other.module == module && other.classifier == classifier
            }
            return false
        }

        override fun hashCode(): Int {
            var result = group.hashCode()
            result = 31 * result + module.hashCode()
            result = 31 * result + classifier.hashCode()
            return result
        }

        override fun toString(): String {
            return "ImportGroupData(group='$group', module='$module', version='$version', classifier='$classifier')"
        }
    }

    private class ImportData(private val constructSource: String, classifier: String, val fileName: String) {
        // The main Maven is used by default
        var mainSource: String = UrlData["Maven"]!!
        val downURLConstruct = if (classifier.isBlank()) constructSource else "$constructSource-$classifier"

        fun getDownUrl(): String {
            return if (constructSource.startsWith("/")) {
                "${mainSource}$downURLConstruct.jar"
            } else {
                "${mainSource}/$downURLConstruct.jar"
            }
        }

        fun getJarVerifyHash(): String = getDownUrl() + ".md5"


        fun getDownPom(mainSource: String): String {
            return if (constructSource.startsWith("/")) {
                "${mainSource}$constructSource.pom"
            } else {
                "${mainSource}/$constructSource.pom"
            }
        }

        fun getPomVerifyHash(mainSource: String): String = getDownPom(mainSource) + ".md5"
    }

    private fun Node.toSeq() = generateSequence(firstChild) { it.nextSibling }

    private fun Node.toMap() = toSeq().associateBy { it.nodeName }

    companion object {
        /** 已经加载的Jar */
        private val loadEnd = Seq<File>()

        /** 需要加载的Jar */
        private val load = Seq<File>()

        /** 需要下载的依赖数据 */
        private val dependenciesDown = Seq<ImportData>()

        /** 需要导入的依赖 */
        private val dependenciesFile = Seq<File>()
        private val tempGroup = Seq<ImportGroupData>()

        /** 依赖Pom缓存 (上限 1024 条, 防止无限增长) */
        private val pomCache: ObjectMap<String, String>
        private const val POM_CACHE_MAX_SIZE = 1024

        private val httpManager = HttpRequestManage(
            OkHttpClient.Builder().also { builder ->
                builder.retryOnConnectionFailure(true)
                builder.connectTimeout(3, TimeUnit.SECONDS)
                builder.readTimeout(3, TimeUnit.SECONDS)
                builder.writeTimeout(3, TimeUnit.SECONDS)
            }.build()
        )

        val UrlData = ObjectMap<String, String>().apply {
            // Maven
            this["Maven"] = "https://repo1.maven.org/maven2"
            // Maven China
            // 腾讯, 行
            this["MavenTencent"] = "https://mirrors.cloud.tencent.com/nexus/repository/maven-public"
            // 阿里, 更新不及时
            this["MavenAli"] = "https://maven.aliyun.com/repository/central"
            // 网易, 不存在的是现拉
            this["MavenNetease"] = "https://mirrors.163.com/maven/repository/maven-public"
            // 华为, 很奇怪, 文件直接是下载
            this["MavenHuaWei"] = "https://repo.huaweicloud.com/repository/maven"
            // 第三方库
            this["JitPack"] = "https://jitpack.io"
        }

        init {
            val serializers = SerializersEnum.Bin.create().apply {
                (this as MachineFriendlySerializers).code = CompressionAlgorithm.SevenZip
            }
            val pomCacheBin = PluginData(serializers).apply {
                setFileUtil(FileUtils.getFolder(Data.ServerLibPath).toFile("libData.bin"))
            }

            pomCache = pomCacheBin.get("pomCache", ObjectMap())

            Threads.addSavePool {
                pomCacheBin["pomCache"] = pomCache

                FileUtils.getFolder(Data.ServerLibPath).fileListNotNullSize.eachAll {
                    if (it.name.endsWith("jar") && !loadEnd.contains(it)) {
                        it.delete()
                    }
                }
            }
        }
    }
}
