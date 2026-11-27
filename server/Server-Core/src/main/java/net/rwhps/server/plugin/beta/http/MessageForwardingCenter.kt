/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.beta.http

import io.netty.handler.codec.http.HttpHeaderValues
import net.rwhps.server.game.manage.ModManage
import net.rwhps.server.net.core.web.WebGet
import net.rwhps.server.net.core.web.WebPost
import net.rwhps.server.net.http.AcceptWeb
import net.rwhps.server.net.http.SendWeb
import net.rwhps.server.plugin.beta.http.WebStatus.Companion.toWebStatusJson
import net.rwhps.server.plugin.beta.http.data.GetData
import net.rwhps.server.plugin.beta.http.post.run.ClientCommand
import net.rwhps.server.plugin.beta.http.post.run.ServerCommand
import net.rwhps.server.util.inline.toJson

/**
 * @date  2023/6/27 10:49
 * @author Dr (dr@der.kim)
 */
class MessageForwardingCenter {
    val getCategorize = object: WebGet() {
        override fun get(accept: AcceptWeb, send: SendWeb) {
            println("收到GET请求: ${accept.getUrl}")
            send.setConnectType(HttpHeaderValues.APPLICATION_JSON)
            when (accept.getUrl.removePrefix("/${RwHpsWebApiMain.name}/api/get/")) {
                "event/GameOver" -> {
                    println("处理GameOver事件")
                    send.setData(GetData.GameOverPositive.data.data.toList().toJson().toWebStatusJson())
                    GetData.GameOverPositive.data.data.clear()
                }
                "info/SystemInfo" -> {
                    println("获取系统信息")
                    val systemInfo = GetData.SystemInfo()
                    println("获取到的系统信息: $systemInfo")
                    send.setData(systemInfo.toJson().toWebStatusJson())
                }
                "info/GameInfo" -> {
                    println("获取游戏信息")
                    sendSnapshot(send) { ManagementStateSupport.gameInfo() }
                }
                "info/ModsInfo" -> {
                    println("获取模组信息")
                    val modsInfo = ModManage.getModsList()
                    println("获取到的模组信息: $modsInfo")
                    send.setData(modsInfo.toJson().toWebStatusJson())
                }
                "info/StartState" -> send.setData(net.rwhps.server.plugin.internal.headless.support.StartModeAutoStartSupport.buildAutoStartDiagnostics().toJson().toWebStatusJson())
                "info/WorldState" -> sendSnapshot(send) { ManagementStateSupport.worldState() }
                "server-info" -> sendSnapshot(send) { ManagementStateSupport.serverInfo() }
                "info/RelayInfo" -> {
                    val relayInfo = GetData.RelayInfo()
                    send.setData(relayInfo.toJson().toWebStatusJson())
                }
                else -> {
                    println("404 未找到: ${accept.getUrl}")
                    send.send404(false)
                }
            }
            send.send()
        }
    }

    private fun sendSnapshot(send: SendWeb, capture: () -> Any) {
        try { send.setData(capture().toJson().toWebStatusJson()) }
        catch (error: Exception) {
            send.status = io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE
            send.setData("Snapshot unavailable; retry after the game thread is ready.".toWebStatusJson("UNAVAILABLE"))
        }
    }

    val postCategorize = object: WebPost() {
        override fun post(accept: AcceptWeb, send: SendWeb) {
            send.setConnectType(HttpHeaderValues.APPLICATION_JSON)
            when (accept.getUrl.removePrefix("/${RwHpsWebApiMain.name}/api/post/")) {
                "run/${ServerCommand.prefixURL}" -> ServerCommand.post(accept, send)
                "run/${ClientCommand.prefixURL}" -> ClientCommand.post(accept, send)
                else -> send.send404(false)
            }
            send.send()
        }
    }
}
