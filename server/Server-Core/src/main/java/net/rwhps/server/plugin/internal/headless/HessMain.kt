/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless

import net.rwhps.server.data.global.Data
import net.rwhps.server.func.StrCons
import net.rwhps.server.game.event.EventGlobalManage
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.plugin.Plugin
import net.rwhps.server.plugin.internal.headless.service.event.GameHeadlessEventGlobal
import net.rwhps.server.util.classload.GameModularReusableLoadClass
import net.rwhps.server.util.game.GameStartInit
import net.rwhps.server.util.game.command.CommandHandler
import net.rwhps.server.util.log.Log
import java.util.*

/**
 * @author Dr (dr@der.kim)
 */
class HessMain: Plugin() {
    override fun registerGlobalEvents(eventManage: EventGlobalManage) = eventManage.registerListener(GameHeadlessEventGlobal())

    override fun registerCoreCommands(handler: CommandHandler) {
        handler.register("start", "serverCommands.start") { _: Array<String>?, log: StrCons ->
            if (Data.startServer) {
                log("The server is not closed, please close")
                return@register
            }
            Data.startServer = true

            // Start Hess Core
            var load = GameModularReusableLoadClass(
                    Thread.currentThread().contextClassLoader, Thread.currentThread().contextClassLoader.parent
            )
            // D-04: 初始化失败必须中止 start，否则后续 start() 会在未装载任何游戏类的
            // ClassLoader 上 findClass("...java.Main")!! 抛 NPE（后台线程静默死亡）。
            if (GameStartInit.init(load)) {
                Log.clog(Data.i18NBundle.getinput("server.load.headless"))
                // 设置 RW-HPS 主要使用的 Hess
                HeadlessModuleManage.hpsLoader = load.toString()
                GameStartInit.start(load)
            } else {
                Data.startServer = false
                Log.fatal("[Headless] GameStartInit.init failed: game resources/classes are not available. Aborting 'start'.")
                log("Headless init failed; see log for details. Server not started.")
            }

//             load = GameModularReusableLoadClass(
//                    Thread.currentThread().contextClassLoader, Thread.currentThread().contextClassLoader.parent
//            )
//            GameStartInit.init(load)
//            Log.clog(Data.i18NBundle.getinput("server.load.headless"))
//            // 设置 RW-HPS 主要使用的 Hess
//            HeadlessModuleManage.hpsLoader = load.toString()
//            GameStartInit.start(load)

            Log.set(Data.config.log.uppercase(Locale.getDefault()))
        }
    }

    override fun registerServerCommands(handler: CommandHandler) {
        serverServerCommands = handler
    }

    companion object {
        internal lateinit var serverServerCommands: CommandHandler
    }
}