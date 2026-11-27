/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package com.corrodinggames.rts.gameFramework.j

import net.rwhps.server.data.global.NetStaticData.netService
import net.rwhps.server.game.manage.IRwHpsManage
import net.rwhps.server.net.NetService
import net.rwhps.server.net.core.IRwHps
import net.rwhps.server.plugin.internal.headless.HessMain
import net.rwhps.server.plugin.internal.headless.inject.core.GameEngine
import net.rwhps.server.util.inline.findField
import net.rwhps.server.util.log.Log
import java.io.Closeable
import com.corrodinggames.rts.gameFramework.j.ao as ServerAcceptRunnable
import com.corrodinggames.rts.gameFramework.l as GameEe

/**
 * 覆写 Game-Lib 的端口监听, 来实现 BIO->NIO
 *
 * @property netEngine NetEngine
 * @property netService NetService
 * @property port Int
 * @constructor
 *
 * @author Dr (dr@der.kim)
 */
class CustomServerSocket(var1: ad): ServerAcceptRunnable(var1), Closeable {
    private val netEngine: ad = this::class.java.findField("r", ad::class.java)!!.get(this)!! as ad
    private var netServiceID = NetService.coreID()
    private var port = 0

    /**
     * 启动线程, 开启端口
     */
    override fun run() {
        if (f) {
            Log.clog("Does not support UDP")
            return
        }
        GameEe.aq()
        Thread.currentThread().name = "NewConnectionWorker-" + (if (f) "udp" else "tcp") + " - " + this.e

        val iRwHps = IRwHpsManage.addIRwHps(this::class.java.classLoader, IRwHps.NetType.ServerProtocol)
        GameEngine.iRwHps = iRwHps
        HessMain.serverServerCommands.handleMessage("startnetservice $netServiceID true $port", iRwHps)
    }

    /**
     * 关闭端口监听
     */
    override fun b() {
        close()
    }

    /**
     * 监听端口
     *
     * @param udp 是否是 UDP
     */
    override fun a(udp: Boolean) {
        startPort(udp)
    }

    /**
     * 监听端口
     *
     * @param udp 是否是 UDP
     */
    private fun startPort(udp: Boolean) {
        f = udp
        port = netEngine.m
        Log.debug("[ServerSocket] starting socket.. ${if (udp) "udp" else "tcp"} port: $port")
    }

    /**
     * 关闭端口监听
     */
    override fun close() {
        Log.debug("[Close]")
        val service = netService.find { it.id == netServiceID }
        if (service == null) {
            Log.warn("[ServerSocket] Net service $netServiceID is already stopped or missing; skip duplicate close.")
            GameEngine.iRwHps = null
            return
        }
        runCatching { service.stop() }
            .onFailure { Log.warn("[ServerSocket] Failed to stop net service $netServiceID during close.", it) }
        GameEngine.iRwHps = null
    }
}
