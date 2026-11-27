/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.beta.http

import net.rwhps.server.game.event.EventGlobalManage
import net.rwhps.server.game.event.EventManage
import net.rwhps.server.game.event.core.EventListenerHost
import net.rwhps.server.game.event.global.ServerConsolePrintEvent
import net.rwhps.server.plugin.Plugin
import net.rwhps.server.plugin.beta.http.data.GetData
import net.rwhps.server.plugin.beta.http.data.HttpApiEvent
import net.rwhps.server.util.annotations.core.EventListenerHandler

/**
 * @date  2023/6/27 11:11
 * @author Dr (dr@der.kim)
 */
class RwHpsWebApiMain: Plugin() {
    override fun onEnable() {
        val mc = MessageForwardingCenter()
        val auth = Authentication(mc)
        auth.registerAuthenticationCenter()
        
        registerServerInfoApi()
    }

    private fun registerServerInfoApi() {
        registerApi("/HttpApi/api/get/server-info") { request: MyHttpRequest, response: MyHttpResponse ->
            val onlinePlayers = getOnlinePlayersCount()
            val totalRooms = getTotalRoomsCount()
            val ongoingRooms = getOngoingRoomsCount()
            val notStartedRooms = getNotStartedRoomsCount()
            
            val jsonResponse = """
                {
                    "onlinePlayers": $onlinePlayers,
                    "totalRooms": $totalRooms,
                    "ongoingRooms": $ongoingRooms,
                    "notStartedRooms": $notStartedRooms
                }
            """.trimIndent()
            
            response.setContentType("application/json")
            response.write(jsonResponse)
        }
    }

    private fun getOnlinePlayersCount(): Int {
        return 0 // 示例返回值
    }

    private fun getTotalRoomsCount(): Int {
        return 0 // 示例返回值
    }

    private fun getOngoingRoomsCount(): Int {
        return 0 // 示例返回值
    }

    private fun getNotStartedRoomsCount(): Int {
        return 0 // 示例返回值
    }

    private fun registerApi(path: String, handler: (MyHttpRequest, MyHttpResponse) -> Unit) {
        apiRoutes[path] = handler
    }

    private val apiRoutes = mutableMapOf<String, (MyHttpRequest, MyHttpResponse) -> Unit>()

    fun handleRequest(request: MyHttpRequest, response: MyHttpResponse) {
        val path = request.getPath() // 获取请求路径
        println("Received request for path: $path") // 调试信息
        val handler = apiRoutes[path]
        if (handler != null) {
            handler(request, response)
        } else {
            response.write("404 Not Found")
            response.setContentType("text/plain")
        }
    }

    companion object {
        const val name = "HttpApi"
        const val cookieName = "HttpApi-Authentication"
    }
}

class MyHttpRequest(private val path: String) {
    fun getPath(): String {
        return path
    }
}

class MyHttpResponse {
    private var contentType: String = ""
    private var content: String = ""

    fun setContentType(type: String) {
        contentType = type
    }

    fun write(content: String) {
        this.content = content
        // 这里可以添加逻辑将内容写入响应
        println("Response Content-Type: $contentType")
        println("Response Body: $content")
    }
}