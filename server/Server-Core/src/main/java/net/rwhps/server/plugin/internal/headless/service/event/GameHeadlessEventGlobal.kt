/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.service.event

import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.global.Data
import net.rwhps.server.data.global.Statisticians
import net.rwhps.server.game.event.core.EventListenerHost
import net.rwhps.server.game.event.global.ServerHessLoadEvent
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.manage.ModManage
import net.rwhps.server.plugin.beta.UpListMain
import net.rwhps.server.plugin.internal.headless.support.StartModeMapLockSupport
import net.rwhps.server.util.annotations.core.EventListenerHandler
import net.rwhps.server.util.log.Log
import java.util.concurrent.TimeUnit

/**
 * @author Dr (dr@der.kim)
 */
@Suppress("UNUSED")
class GameHeadlessEventGlobal: EventListenerHost {

    @EventListenerHandler
    fun registerGameLibLoadEvent(serverHessLoadEvent: ServerHessLoadEvent) {
        if (HeadlessModuleManage.hpsLoader != serverHessLoadEvent.loadID) {
            Log.clog(Data.i18NBundle.getinput("server.headless.oks", Statisticians.computeTime("Core.Headless.${serverHessLoadEvent.loadID}"), serverHessLoadEvent.loadID))
            return
        }

        /* Load Mod */
        Log.clog(Data.i18NBundle.getinput("server.loadMod", ModManage.load()))
        Log.clog(Data.i18NBundle.getinput("server.headless.ok", Statisticians.computeTime("Core.Headless.${serverHessLoadEvent.loadID}")))

        if (Data.configServer.modsLoadErrorPrint) {
            Log.clog("[${serverHessLoadEvent.loadID}]: ${serverHessLoadEvent.gameModule.gameUnitData.getRwModLoadInfo().joinToString(Data.LINE_SEPARATOR)}")
        }

        var passwd: String? = null
        if (Data.configServer.passwd.isNotBlank()) {
            passwd = Data.configServer.passwd
        }
        if (!HeadlessModuleManage.hps.gameLinkNet.startHeadlessServer(Data.config.port, passwd)) {
            Log.error("[StartMode] Headless server failed to start on port ${Data.config.port}; startup flow stopped.")
            return
        }
        StartModeMapLockSupport.applyLockedMapIfNeeded()
        Log.clog(Data.i18NBundle.getinput("server.headless.run", serverHessLoadEvent.loadID))

        if (Data.config.autoUpList) {
            Threads.newThreadOther {
                TimeUnit.SECONDS.sleep(2)
                UpListMain.ensureAutoUpListStarted("headlessStart")
            }
        }
    }
}
