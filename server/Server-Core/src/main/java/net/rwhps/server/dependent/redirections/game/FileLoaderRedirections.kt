/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.dependent.redirections.game

import net.rwhps.asm.data.MethodTypeInfoValue
import net.rwhps.server.data.global.Data
import net.rwhps.server.dependent.redirections.MainRedirections
import net.rwhps.server.dependent.redirections.slick.SilckClassPathProperties
import net.rwhps.server.io.input.DisableSyncByteArrayInputStream
import net.rwhps.server.io.output.ByteArrayOutputStream
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.struct.map.OrderedMap
import net.rwhps.server.util.ReflectionUtils
import net.rwhps.server.util.annotations.mark.AsmMark
import net.rwhps.server.util.annotations.mark.GameSimulationLayer
import net.rwhps.server.util.classload.GameModularLoadClass
import net.rwhps.server.util.compression.CompressionDecoderUtils
import net.rwhps.server.util.file.FileName
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.inline.*
import net.rwhps.server.util.log.Log
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.*
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import javax.imageio.ImageIO

//关闭傻逼格式化
//@formatter:off

/**
 * 通过 ASM 来覆写 游戏的文件系统来达到自定义位置
 * 不要骂了 只能这样写了
 *
 * @property font 把字体放在ZIP内来减少大小
 * @author Dr (dr@der.kim)
 */
@AsmMark.ClassLoaderCompatible
@GameSimulationLayer.GameSimulationLayer_KeyWords("FileLoader: ")
class FileLoaderRedirections: MainRedirections {
    private val font by lazy {
        CompressionDecoderUtils.sevenZip(FileUtils.getFolder(Data.Plugin_GameCore_Data_Path).toFile("Game-Fonts.7z").file).getZipAllBytes()
    }
    private val fileSystemAsm = "FileLoader-ASM: "
    private val fileSystemLocation = FileUtils.getFolder(Data.Plugin_GameCore_Data_Path).file

    init {
        // Mkdie Mods Folder
        FileUtils.getFolder(Data.Plugin_Mods_Path).mkdir()
        // 清理Hess的数据, 避免启用安全模式
        FileUtils.getFolder(Data.Plugin_GameCore_Data_Path).toFile("preferences.ini").delete()
        // 清除无用缓存
        FileUtils.getFolder(Data.ServerCachePath).delete()
    }

    override fun register() {
        /* 修改 每个加载器下 [ResourceLoader] 的初始化实现, 来为 [ResourceLoader] 实现自定义内容 */
        redirectR(MethodTypeInfoValue("org/newdawn/slick/util/ResourceLoader", "<clinit>", "()V")) { obj: Any, _: String, _: Class<*>, _: Array<out Any?> ->
            val classIn = obj as Class<*>
            val classLoader = classIn.classLoader

            val resourceLoader = SilckClassPathProperties.ResourceLoader.toClass(classLoader)!!
            val list = ArrayList<Any>()

            val load = if (classLoader is GameModularLoadClass) {
                classLoader.loadClassBytes(SilckClassPathProperties.DrFileSystemLocation, SilckClassPathProperties.DrFileSystemLocation.readAsClassBytes())!!
            } else {
                SilckClassPathProperties.DrFileSystemLocation.toClass(classLoader)!!
            }

            list.add(load.accessibleConstructor(OrderedMap::class.java).newInstance(font))
            list.add(SilckClassPathProperties.ClasspathLocation.toClass(classLoader)!!.accessibleConstructor().newInstance())
            list.add(SilckClassPathProperties.FileSystemLocation.toClass(classLoader)!!.accessibleConstructor(File::class.java).newInstance(fileSystemLocation))
            resourceLoader.findField("locations")!!.set(null, list)
            return@redirectR null
        }

        /* 屏蔽游戏设置保存 */
        @GameSimulationLayer.GameSimulationLayer_KeyWords("preferences.ini")
        redirectR(MethodTypeInfoValue("com/corrodinggames/rts/gameFramework/SettingsEngine", "saveToFileSystem", "()Z")) { obj: Any, _: String, _: Class<*>, _: Array<out Any?> ->
            "com.corrodinggames.rts.gameFramework.l".toClassAutoLoader(obj)!!.findMethod("b", String::class.java)!!
                .invoke(null, "Saving settings: RW-HPS(ASM)")
            return@redirectR true
        }
        redirectR(MethodTypeInfoValue("com/corrodinggames/rts/gameFramework/SettingsEngine", "loadFromFileSystem", "()V"))

        // 重定向部分文件系统 (mods maps replay)
        val filePath = FileUtils.getPath(Data.ServerDataPath) + "/"
        // 设置 重定向文件PATH类
        redirectR(MethodTypeInfoValue("com/corrodinggames/rts/gameFramework/e/c", "f", "()Ljava/lang/String;")) { _: Any, _: String, _: Class<*>, _: Array<out Any?> ->
            filePath
        }
        redirectR(MethodTypeInfoValue("com/corrodinggames/rts/gameFramework/e/c", "b", "()Ljava/lang/String;")) { _: Any, _: String, _: Class<*>, _: Array<out Any?> ->
            filePath
        }

        // 重定向资源文件系统 (Res FileSystem)
        val resAndAssetsPath = FileUtils.getPath(Data.Plugin_GameCore_Data_Path) + "/"
        redirectR(MethodTypeInfoValue("com/corrodinggames/rts/gameFramework/e/c", "a", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            val listFiles: Seq<File> = FileUtils.getFolder(Data.Plugin_GameCore_Data_Path).toFolder(args[0].toString()).fileList
            for (file in listFiles) {
                val name: String = FileName.getFileNameNoSuffix(file.name)
                if (name == args[1]) {
                    return@redirectR "${resAndAssetsPath}${args[0]}/${file.name}"
                }
            }
            return@redirectR null
        }

        // 重定向资源文件系统 (Assets FileSystem)
        redirectR(MethodTypeInfoValue("android/content/res/AssetManager", "a", "(Ljava/lang/String;I)Ljava/io/InputStream;")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            val name = args[0].toString()
            return@redirectR FakeHessRes.fakeImageAndMusic(name) ?:FileInputStream("${resAndAssetsPath}assets/$name")
        }

        // 重定向 流系统
        redirectR(MethodTypeInfoValue("com/corrodinggames/rts/gameFramework/e/c", "i", "(Ljava/lang/String;)Lcom/corrodinggames/rts/gameFramework/utility/j;")) { obj: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            var str = args[0].toString().replace(resAndAssetsPath, "").replace("\\", "/")

            if (str.startsWith("assets/") || str.startsWith("assets\\")) {
                str = str.substring("assets/".length)
            }
            val str2: String = str
            val str3 = resAndAssetsPath + "assets/" + str2
            return@redirectR try {
                try {
                    // AssetManager
                    findNewClass_AssetInputStream(obj, InputStream::class.java, String::class.java, String::class.java).newInstance(
                            FakeHessRes.fakeImageAndMusic(str2) ?:FileInputStream("${resAndAssetsPath}assets/${str2}")
                            , str3, str2
                    )
                } catch (e: FileNotFoundException) {
                    null
                }
            } catch (e2: IOException) {
                Log.error(fileSystemAsm + "Could not find asset:" + str3)
                null
            }
        }

        redirectR(MethodTypeInfoValue("com/corrodinggames/rts/gameFramework/e/c", "f", "(Ljava/lang/String;)Ljava/lang/String;")) { obj: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            var d: String = (run_FileSystem(obj, "d", String::class.java).invoke(obj, args[0].toString()) as String).replace("\\", "/")

            val path = args[0].toString().replace("\\", "/")
            // 跳过已经绝对路径的
            if (path.contains(filePath)) {
                return@redirectR path
            }

            /* 覆写 MOD 加载器路径 */
            val overrideModLoad = fun(path: String): String {
                val modFolder = "mods/units"
                val mapFolder = "mods/maps"

                val find: (String, String, String) -> String? = { pathInA, pathInB, toPath ->
                    if (pathInA.startsWith(pathInB)) {
                        if (pathInA == pathInB) {
                            FileUtils.getPath(toPath)
                        } else {
                            // 一个小问题, 来自 splicePath , 他会默认在屁股后面加一个 /
                            FileUtils.splicePath(FileUtils.getPath(toPath), pathInA.substring(pathInB.length))
                        }
                    } else {
                        null
                    }
                }

                find(path, modFolder, Data.Plugin_Mods_Path)?.run {
                    return this
                }
                find(path, mapFolder, Data.ServerMapsPath)?.run {
                    // Luke奇怪的方案, 只能手动加个 / 来触发读取, 不然就成了资源路径 (Assets)
                    return "/$this"
                }


                return resAndAssetsPath + path
            }

            val cc = ReflectionUtils.findField(Class.forName("com.corrodinggames.rts.gameFramework.l", true, obj::class.java.classLoader), "aU", Boolean::class.java)!!

            return@redirectR if (cc[null] as Boolean) {
                if (d.startsWith("/SD/rusted_warfare_maps")) {
                    d = "/SD/mods/maps" + d.substring("/SD/rusted_warfare_maps".length)
                }
                if (d.startsWith("/SD/rustedWarfare/maps")) {
                    d = "/SD/mods/maps" + d.substring("/SD/rustedWarfare/maps".length)
                }

                if (d.startsWith("/SD/") || d.startsWith("\\SD\\")) {
                    var substring = d.substring("/SD/".length)
                    if (substring.startsWith("rustedWarfare/")) {
                        substring = substring.substring("rustedWarfare/".length)
                    }
                    overrideModLoad(substring)
                } else if ((run_FileSystem(obj, "c", String::class.java).invoke(obj, d) as Boolean)) {
                    d
                } else {
                    resAndAssetsPath + "assets/$d"
                }
            } else if (d.startsWith("/SD/")) {
                var substring2 = d.substring("/SD/".length)
                if (substring2.startsWith("rustedWarfare/")) {
                    substring2 = substring2.substring("rustedWarfare/".length)
                }
                overrideModLoad(substring2)
            } else {
                d
            }
        }
    }


    private fun run_FileSystem(obj: Any, method: String, vararg paramTypes: Class<*>): Method {
        return ReflectionUtils.findMethod(
                Class.forName("com.corrodinggames.rts.gameFramework.e.c", true, obj::class.java.classLoader), method, *paramTypes
        )!!
    }

    // J
    private fun findNewClass_AssetInputStream(obj: Any, vararg paramTypes: Class<*>): Constructor<*> {
        return ReflectionUtils.accessibleConstructor(
                Class.forName("com.corrodinggames.rts.gameFramework.utility.j", true, obj::class.java.classLoader), *paramTypes
        )
    }

    private object FakeHessRes {
        private val imagePngBytes = fakeImage("png")
        private val imageJpgBytes = fakeImage("jpg")

        fun fakeImageAndMusic(name: String): InputStream? {
            return when (name.substring(name.lastIndexOf(".") + 1)) {
                "png" -> DisableSyncByteArrayInputStream(imagePngBytes)
                "jpg" -> DisableSyncByteArrayInputStream(imageJpgBytes)
                "ogg" -> DisableSyncByteArrayInputStream(byteArrayOf(0))
                else -> null
            }
        }

        private fun fakeImage(imageType: String): ByteArray {
            val width = 128
            val height = 128

            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g2d = image.createGraphics()
            g2d.color = Color(0, 0, 0, 0)
            g2d.fillRect(0, 0, width, height)
            g2d.dispose()

            val outputFile = ByteArrayOutputStream()
            ImageIO.write(image, imageType, outputFile)
            return outputFile.toByteArray()
        }
    }
}