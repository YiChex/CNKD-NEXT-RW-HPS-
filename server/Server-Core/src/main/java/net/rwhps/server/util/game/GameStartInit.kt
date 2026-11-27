/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.game

import net.rwhps.server.data.global.Data
import net.rwhps.server.data.global.Statisticians
import net.rwhps.server.net.manage.DownloadManage
import net.rwhps.server.plugin.internal.headless.service.data.HessClassPathProperties
import net.rwhps.server.util.classload.GameModularLoadClass
import net.rwhps.server.util.classload.GameModularReusableLoadClass
import net.rwhps.server.util.compression.CompressionDecoderUtils
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.log.Log
import java.lang.reflect.Method
import kotlin.concurrent.thread

/**
 * 游戏 资源文件 初始化
 *
 * @author Dr (dr@der.kim)
 */
object GameStartInit {
    /** D-01: maximum number of stale redirection targets listed individually in the log. */
    private const val MAX_REPORTED_REDIRECTION_GAPS = 20

    private val gameCorePath = FileUtils.getFolder(Data.Plugin_GameCore_Data_Path, true)

    private enum class ResMD5(val md5: String, val fileUtils: FileUtils) {
        Res("408aa02d8566a771c5ad97caf9f1f701", gameCorePath.toFile("Game-Res.7z")),
        Fonts("e27f86783a04bb6c7bc7b4388f8c8539", gameCorePath.toFile("Game-Fonts.7z")),
        Assets("7c50a14a5d2c6570e0b815d146c446f0", gameCorePath.toFile("Game-Assets.7z")),
        GameModularReusableClass("823515512b13234b6607927b599acbf7", gameCorePath.toFile("GameModularReusableClass.bin"))
    }

    /**
     * D-03: 删除一个（可能是只读的）资源文件。
     *
     * 原实现直接调用 [FileUtils.delete]，其内部在 `File.delete()` 返回 false 时
     * 抛出的 IOException 会被吞掉并返回 false，而调用方忽略了返回值。
     * 在 Windows 上只读文件无法被 `File.delete()` 删除，导致损坏的资源文件
     * 既删不掉、也不会被重新下载，自愈链彻底断裂。
     *
     * @return 删除成功或文件本就不存在时返回 true
     */
    private fun forceDelete(target: FileUtils): Boolean {
        if (target.notExists()) {
            return true
        }
        // 递归解除只读，否则 Windows 上 File.delete() 静默失败
        runCatching {
            target.file.setWritable(true, false)
            if (target.file.isDirectory) {
                target.file.walkBottomUp().forEach { it.setWritable(true, false) }
            }
        }
        target.delete()
        val gone = target.notExists()
        if (!gone) {
            Log.error("[Headless] Failed to delete stale game resource: ${target.path}")
        }
        return gone
    }

    /**
     * D-01 / D-02, corrected by A1-02: report a stale game-class cache WITHOUT overstating it.
     *
     * When the server runs from `GameModularReusableClass.bin`, `asmClass()` returns the baked
     * bytes as-is rather than transforming them. It is tempting to conclude that a hook registered
     * after the cache was built therefore never runs -- and that is exactly what an earlier
     * investigation concluded, incorrectly, about the `game/i` loop-entry drain hook.
     *
     * It is wrong: `HeadlessProxyClass.initProxyClass()` calls `AsmAgent.agentmain(...)`
     * unconditionally, and an Instrumentation `ClassFileTransformer` still fires at defineClass even
     * when the input bytes came from the cache. So a hook absent from the bake is normally applied
     * at load time anyway. (Independently verified with the real agent against the real .bin bytes.)
     *
     * What a gap therefore means is build hygiene -- the bake predates the redirection table, so
     * load-time transformation does work the cache was meant to pre-do -- NOT a correctness failure.
     * The cache cannot be rebuilt from itself; rebuild it from pristine game classes via
     * [GameModularReusableLoadClass.saveData] if you want to remove that load-time work.
     */
    private fun reportBakedRedirectionGaps(load: GameModularReusableLoadClass) {
        try {
            val missing = load.findMissingBakedRedirections()
            if (missing.isEmpty()) {
                Log.debug("[Headless] Game-class cache contains every registered redirection hook.")
                return
            }
            /*
             * A1-02 (independent audit): this warning used to claim the listed classes "will run
             * un-redirected". That is a FALSE POSITIVE, and a costly one -- it is what convinced an
             * earlier investigation that a new hook would never take effect, leading to an
             * unnecessary revert.
             *
             * Why it is false: HeadlessProxyClass.initProxyClass() calls AsmAgent.agentmain(...)
             * unconditionally, registering a global ClassFileTransformer. Instrumentation
             * transformers still fire at defineClass even when the .bin bytes are used as-is, so a
             * hook missing from the bake is normally applied at load time anyway. The audit proved
             * this with the real agent against the real .bin bytes.
             *
             * What the gap DOES mean: the bake is older than the redirection table, so load-time
             * transformation is doing work the cache was supposed to have pre-done. That is a
             * build-hygiene signal, not a correctness failure. Say exactly that.
             */
            Log.warn(
                "[Headless] Game-class cache is older than the redirection table: ${missing.size} " +
                    "registered target(s) carry no baked hook:"
            )
            missing.take(MAX_REPORTED_REDIRECTION_GAPS).forEach { Log.warn("[Headless]   missing baked hook: $it") }
            if (missing.size > MAX_REPORTED_REDIRECTION_GAPS) {
                Log.warn("[Headless]   ... and ${missing.size - MAX_REPORTED_REDIRECTION_GAPS} more")
            }
            Log.warn(
                "[Headless] These hooks are still applied at class-load time by the ASM agent " +
                    "(HeadlessProxyClass -> AsmAgent.agentmain), so they are NOT silently missing. " +
                    "Rebuilding GameModularReusableClass.bin from pristine game classes would only " +
                    "remove the load-time work; it is not required for correctness."
            )
        } catch (e: Exception) {
            Log.error("[Headless] Failed to verify baked redirection hooks", e)
        }
    }

    fun init(load: GameModularReusableLoadClass): Boolean {
        try {
            val temp = FileUtils.getFolder(Data.Plugin_GameCore_Data_Path, true)

            /* 鉴别两个文件的MD5, 不相同则删除 */
            ResMD5.values().forEach {
                if (it.fileUtils.exists() && it.md5 != it.fileUtils.md5) {
                    Log.debug("File MD5 DoesNotMatch", it.name)
                    if (!forceDelete(it.fileUtils)) {
                        // 删不掉就不能继续：损坏的 .bin/7z 会让整个 Headless 起不来
                        Log.fatal("[Headless] Game resource is corrupted and could not be removed: ${it.fileUtils.path}")
                        return false
                    }
                }
            }

            // D-03 / D-04: 返回是否成功，失败时必须让 init() 返回 false 而不是静默继续。
            val resTask: (FileUtils, String, Boolean) -> Boolean = { file, resName, unzip ->
                var ok = true
                // D-03: 原为 `!exists() && length()==0`，文件存在但长度为 0 时整段被跳过。
                if (!file.exists() || file.length() == 0L) {
                    if (unzip) {
                        temp.toFolder(resName).file.delete()
                    }
                    // 删除残留的空/截断文件，否则下载会因目标已存在而失败
                    if (!forceDelete(file)) {
                        Log.fatal("[Headless] Cannot remove broken game resource before download: ${file.path}")
                        ok = false
                    } else {
                        DownloadManage.addDownloadTask(
                            DownloadManage.DownloadData(
                                Data.urlData.readString("Get.Core.ResDown") + file.name, file, progressFlag = true
                            )
                        ).also {
                            Log.clog("$resName : {0}", it)
                        }
                        if (file.notExists() || file.length() == 0L) {
                            Log.fatal("[Headless] Game resource download did not produce a usable file: ${file.path}")
                            ok = false
                        } else {
                            file.setReadOnly()
                        }
                    }
                }
                if (ok && unzip && !temp.toFolder(resName).toFile("Check").exists()) {
                    CompressionDecoderUtils.sevenZip(file.file).use {
                        it.getZipAllBytes().eachAll { filePath, bytes ->
                            temp.toFile(filePath).writeFileByte(bytes)
                        }
                    }
                    temp.toFolder(resName).toFile("Check").also {
                        it.mkdir()
                        it.setReadOnly()
                    }
                }
                ok
            }

            if (!resTask(ResMD5.Res.fileUtils, "res", true)) {
                return false
            }
            if (!resTask(ResMD5.Assets.fileUtils, "assets", true)) {
                return false
            }
            if (!resTask(ResMD5.Fonts.fileUtils, "fonts", false)) {
                return false
            }

            if (GameStartInit::class.java.getResourceAsStream("/libs.zip") == null) {
                if (ResMD5.GameModularReusableClass.fileUtils.notExists()) {
                    if (!resTask(ResMD5.GameModularReusableClass.fileUtils, "gameModularReusableClassFile", false)) {
                        return false
                    }
                }
                load.readData(ResMD5.GameModularReusableClass.fileUtils)
                reportBakedRedirectionGaps(load)
            } else {
                // 加载游戏依赖
                CompressionDecoderUtils.zipAllReadStream(GameStartInit::class.java.getResourceAsStream("/libs.zip")!!).use {
                    it.getSpecifiedSuffixInThePackage("jar", true).eachAll { _, v ->
                        load.addSourceJar(v)
                    }
                }
                // 一些必须的覆盖 - 解决Summon爆炸
                //load.addClassBytes("com.corrodinggames.rts.gameFramework.c", CommandArrayListCoverSyncList.build(), true)

                //TODO Save GameModularReusableClass
                //load.saveData(FileUtils.getFolder(Data.ServerDataPath).toFile("GameModularReusableClass.bin"))
            }
        } catch (e: Exception) {
            Log.fatal(e)
            return false
        }
        return true
    }

    fun start(load: GameModularLoadClass) {
        Statisticians.addTime("Core.Headless.$load")
        thread(name = "Start Hess Game", contextClassLoader = load, priority = Thread.MIN_PRIORITY) {
            // Here, several intermediate signal transmission modules are directly injected into this loader
            // Because this loader only has Game-lib.jar
            // 注入 接口
            CompressionDecoderUtils.zipAllReadStream(FileUtils.getMyCoreJarStream()).use {
                it.getZipAllBytes().eachAll { k, v ->
                    if (
                        // 注入接口
                        k.startsWith(HessClassPathProperties.headlessPath.replace(".", "/")) ||
                        // 注入 ASM 的 FastClass, 使其缓解变量问题
                        k.startsWith(HessClassPathProperties.fastASMClassPath.replace(".", "/")) ||
                        // 覆写游戏的一些Class
                        k.startsWith(HessClassPathProperties.GameHessPath.replace(".", "/"))) {
                        val name = k.replace(".class", "")
                        load.addClassBytes(name.replace("/", "."), v)
                    }
                }
            }

            val testAClass: Class<*> = load.findClass("com.corrodinggames.rts.java.Main")!!
            val mainMethod: Method = testAClass.getDeclaredMethod("main", Array<String>::class.java)
            // 禁用软件加速/关声音/关音乐/不渲染
            // 不要使用 "-noresources", 虽然会不渲染, 但是会导致replay等不生成 (因为渲染被关闭了), 如果用其他方案绕过会有一些问题
            mainMethod.invoke(null, arrayOf("-disable_vbos", "-disable_atlas", "-nomusic", "-nosound", "-nodisplay"))
        }
    }

    /**
     * 谢谢您看到这里
     *
     * 在 RW-HPS 的开发历程中, 离不开以下的帮助 !
     *
     * RW-HPS Staff
     * [RukkitDev/Rukkit] Miku
     * [RW-HPS] 全体
     *
     * 感谢一路的陪伴。新的开发不会就此结束，新的征程即将就此开始
     */
}