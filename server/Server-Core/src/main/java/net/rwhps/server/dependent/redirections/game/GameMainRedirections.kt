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
import net.rwhps.asm.redirections.replace.def.BasicDataRedirections
import net.rwhps.server.data.global.Data
import net.rwhps.server.dependent.redirections.MainRedirections
import net.rwhps.server.dependent.redirections.game.clas.GameLoopExceptionGuard
import net.rwhps.server.game.event.global.ServerHessLoadEvent
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.plugin.internal.headless.service.data.HessClassPathProperties
import net.rwhps.server.util.ReflectionUtils
import net.rwhps.server.util.annotations.mark.AsmMark
import net.rwhps.server.util.annotations.mark.GameSimulationLayer
import net.rwhps.server.util.file.plugin.PluginManage
import net.rwhps.server.util.inline.findMethod
import net.rwhps.server.util.inline.toClassAutoLoader
import net.rwhps.server.util.log.Log

//关闭傻逼格式化
//@formatter:off

/**
 * 游戏无头实现
 *
 * @author Dr (dr@der.kim)
 */
@AsmMark.ClassLoaderCompatible
class GameMainRedirections: MainRedirections {
    override fun register() {
        /* Register headless FileSystem */
        FileLoaderRedirections().register()
        /* Register some dependent features */
        CustomRedirections().register()
        /* Register for network blocking */
        NetPacketRedirections().register()
        /* */
        ValueLinkRedirections().register()
        /* 清理废包 */
        CleanRedirections().register()


        // Remove Main-Loop, 使游戏使用 Slick 自带 gameLoop
        redirectRemove(MethodTypeInfoValue("com/corrodinggames/rts/java/b", "gameLoop", "()V"))
        // 为游戏添加一个锁帧 (解决CPU高占用)
        redirectL(MethodTypeInfoValue("com/corrodinggames/rts/java/b", "updateAndRender", "(I)V", true, FPSSleepRedirections::class.java))
        /*
         * FIX-LOOP-GUARD: 把 updateAndRender 的整个方法体包进 try/catch。
         *
         * 线上事故根因：方法体内部的 `NullPointerException: this.bL is null` 逃出
         * `AppGameContainer.gameLoop()` 的循环，再逃出 `java.j.run()`，**杀死主循环线程**；
         * 而 `Main.r` 没有重启路径 ⇒ 服务器变成僵尸实例（继续收包，但所有主线程操作超时，
         * 实测持续约 5 天 19 小时、748 次超时）。
         *
         * 包住之后，任何单帧异常只丢一帧。`VirtualMachineError`/`ThreadDeath` 仍会被重抛。
         */
        redirectWrap(
            MethodTypeInfoValue("com/corrodinggames/rts/java/b", "updateAndRender", "(I)V"),
            GameLoopExceptionGuard::class.java
        )
        /*
         * FIX-LOOP-GUARD (A2-P3, found by independent verification): `updateAndRender` is NOT the
         * only way the loop thread can die.
         *
         * `AppGameContainer.gameLoop()`'s own exception table covers only two narrow ranges
         * (verified by javap: `18..24 -> Exception` around Thread.sleep, and
         * `31..36 -> SlickException` around updateAndRender). Everything after that is OUTSIDE the
         * table:
         *
         *     51: invokevirtual updateFPS()V
         *     54: invokestatic  Display.update()V
         *     57: invokestatic  Display.isCloseRequested()Z
         *
         * An exception from any of those escapes gameLoop() -> start() (whose handler only
         * destroy()s then rethrows) -> j.run() -> and kills the thread, with Main.r written once and
         * therefore no restart path. Same zombie outcome as the b013 incident, different trigger.
         *
         * Wrapping gameLoop() itself closes all of them at once. The inner handlers still run first
         * (they are registered inside this range, and the JVM picks the innermost matching handler),
         * so existing SlickException behaviour -- which stops the loop and leads to a clean
         * System.exit rather than a zombie -- is preserved. Only exceptions that would otherwise
         * have escaped are now contained, and the `while (running())` in start() simply iterates
         * again.
         *
         * Note AppGameContainer ships inside the .bin and is loaded by the game loader, so the
         * runtime agent can transform it.
         */
        redirectWrap(
            MethodTypeInfoValue("org/newdawn/slick/AppGameContainer", "gameLoop", "()V"),
            GameLoopExceptionGuard::class.java
        )


        // 直接空实现 因为意义不大
        addAllReplace("org/newdawn/slick/util/DefaultLogSystem")
        addAllReplace("com/LibRocket")
        addAllReplace("com/corrodinggames/librocket/scripts/ScriptEngine")

        // 这两个 因为 [LibRocket] 如果是空的话, 会被……游戏调用导致 NPE, 所以我们要覆盖掉方法
        // 需要空实现
        redirectR(MethodTypeInfoValue("com/corrodinggames/librocket/b", "closeDocument", "(Lcom/ElementDocument;)V"))
        redirectR(MethodTypeInfoValue("com/corrodinggames/librocket/b", "closeActiveDocument", "()V"))
        // 会被高频调用, 所以设置一个默认返回, 避免走 ASM 默认
        redirectR(MethodTypeInfoValue("com/corrodinggames/librocket/b", "b", "()Z"), BasicDataRedirections.BOOLEANF)

        // 关闭MusicController
        @GameSimulationLayer.GameSimulationLayer_KeyWords("startNewFrame() not supported on SlickLibRocket")
        addAllReplace("com/corrodinggames/rts/java/d/a")
        // 图像渲染
//        @GameSimulationLayer.GameSimulationLayer_KeyWords("reloadFromImageData:")
//        redirectR(MethodTypeInfoValue("com/corrodinggames/rts/java/s", "a", "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)Ljava/nio/ByteBuffer;"), BasicParamsValueRedirections.ReturnSecondParams)
//        // 渲染
//        //addAllReplace("com/corrodinggames/rts/gameFramework/m/e")
        addAllReplace("com/corrodinggames/rts/gameFramework/am", true)
//        // 渲染
//        addAllReplace("com/corrodinggames/rts/java/e", true)

        /* 恢复-Root */
        redirectR(
                MethodTypeInfoValue("com/corrodinggames/librocket/scripts/Root", "resume", "()V")
        ) { obj: Any?, _: String, _: Class<*>, _: Array<out Any?> ->
            val rootClass = "com.corrodinggames.librocket.scripts.Root".toClassAutoLoader(obj!!)
            ReflectionUtils.findField(rootClass, "guiEngine")!!.also {
                ReflectionUtils.makeAccessible(it)
                it[obj]?.let { correspondingObject ->
                    "com.corrodinggames.librocket.a".toClassAutoLoader(obj)!!.findMethod("f")!!.invoke(correspondingObject)
                }
            }
            return@redirectR null
        }
        /* 恢复-Root */
        redirectR(
                MethodTypeInfoValue("com/corrodinggames/librocket/scripts/Root", "resumeNonMenu", "()V")
        ) { obj: Any?, _: String, _: Class<*>, _: Array<out Any?> ->
            val rootClass = "com.corrodinggames.librocket.scripts.Root".toClassAutoLoader(obj!!)
            ReflectionUtils.findField(rootClass, "guiEngine")!!.also {
                ReflectionUtils.makeAccessible(it)
                it[obj]?.let { correspondingObject ->
                    "com.corrodinggames.librocket.a".toClassAutoLoader(obj)!!.findMethod("a", java.lang.Boolean::class.java)!!
                        .invoke(correspondingObject, false)
                }
            }
            return@redirectR null
        }


        /* 取代游戏自己打印的 */
        redirectR(
                MethodTypeInfoValue("android/util/Log", "a", "(ILjava/lang/String;Ljava/lang/String;)I")
        ) { obj: Any?, _: String, _: Class<*>, args: Array<out Any?> ->
            if (Data.config.log == "ALL") {
                args[2]?.let {
                    val classIn = obj as Class<*>
                    val msg = it.toString()
                    println("[${classIn.classLoader}]  :  " + msg)
                }
            }
            0
        }

        // 游戏引擎加载完毕时触发
        @GameSimulationLayer.GameSimulationLayer_KeyWords("Game init finished in")
        redirectL(MethodTypeInfoValue("com/corrodinggames/rts/java/Main", "h", "()V", false)) { obj: Any?, _: String, _: Array<out Any?> ->
            // Enable the interface
            "${HessClassPathProperties.corePath}.GameEngine".toClassAutoLoader(obj!!)!!.findMethod("init")!!.invoke(null)

            obj::class.java.classLoader.toString().let { loadID ->
                PluginManage.runGlobalEventManage(ServerHessLoadEvent(loadID, HeadlessModuleManage.hessLoaderMap[loadID]!!))
            }

        }

        // 保存Replay时触发
        @GameSimulationLayer.GameSimulationLayer_KeyWords("Recording replay to:")
        redirectL(MethodTypeInfoValue("com/corrodinggames/rts/gameFramework/ba", "d", "(Ljava/lang/String;)V", true)) { obj: Any?, _: String, args: Array<out Any?> ->
            Log.clog("Save Replay to: {}", args[0].also { replayFileName ->
                HeadlessModuleManage.hessLoaderMap[obj!!::class.java.classLoader.toString()]!!.room.replayFileName = replayFileName.toString()
            }!!)
        }
    }
}