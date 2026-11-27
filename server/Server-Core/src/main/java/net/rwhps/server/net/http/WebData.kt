/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.http

import io.netty.handler.codec.http.HttpRequest
import net.rwhps.server.func.Control
import net.rwhps.server.net.core.web.WebGet
import net.rwhps.server.net.core.web.WebPost
import net.rwhps.server.net.core.web.WebSocket
import net.rwhps.server.net.handler.tcp.StartWebSocket
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.util.log.exp.VariableException

/**
 * @author Dr (dr@der.kim)
 */
class WebData {
    private val getData: ObjectMap<String, WebGet> = ObjectMap()
    private val postData: ObjectMap<String, WebPost> = ObjectMap()
    private val getWildcardAllData: ObjectMap<String, WebGet> = ObjectMap()
    private val postWildcardAllData: ObjectMap<String, WebPost> = ObjectMap()
    private val webSocketData: ObjectMap<String, StartWebSocket> = ObjectMap()

    fun addWebGetInstance(url: String, webGet: WebGet) {
        if (getData.containsKey(url)) {
            throw VariableException.RepeatAddException("[AddWebGetInstance] Repeat Add")
        }
        if (url.startsWith(WS_URI)) {
            throw VariableException.TabooAddException("[AddWebGetInstance] TabooA Add, Can not be used WebSocket URL")
        }
        if (url.endsWith("**")) {
            getWildcardAllData[url.removeSuffix("**")] = webGet
        } else {
            getData[url] = webGet
        }
    }

    fun addWebPostInstance(url: String, webPost: WebPost) {
        if (postData.containsKey(url)) {
            throw VariableException.RepeatAddException("[AddWebPostInstance] Repeat Add")
        }
        if (url.startsWith(WS_URI)) {
            throw VariableException.TabooAddException("[AddWebGetInstance] TabooA Add, Can not be used WebSocket URL")
        }
        if (url.endsWith("**")) {
            postWildcardAllData[url.removeSuffix("**")] = webPost
        } else {
            postData[url] = webPost
        }
    }

    fun addWebSocketInstance(url: String, webSocket: WebSocket) {
        if (webSocketData.containsKey(url)) {
            throw VariableException.RepeatAddException("[AddWebSocketInstance] Repeat Add")
        }
        webSocketData[url] = StartWebSocket(webSocket)
    }

    fun removeWebGetInstance(url: String) {
        getData.remove(url)
    }

    fun removeWebPostInstance(url: String) {
        postData.remove(url)
    }

    fun removeWebSocketInstance(url: String) {
        webSocketData.remove(url)
    }

    /**
     * 根据URL 获取实例
     *
     * 通配符优先级低于指定位置 当无法访问指定位置时 尝试使用通配符
     * 如果找不到通配符 将会抛出默认404
     * 建议插件使用 ~/插件名/ 进行开发或兼容
     *
     * @param url String
     * @param sendWeb SendWeb
     */
    internal fun runWebGetInstance(url: String, request: HttpRequest, sendWeb: SendWeb, remoteIp: String) {
        val wildcard = "${url.substring(0, url.lastIndexOf("/") + 1)}*"
        var getUrl = url
        var urlData = ""

        if (url.contains("?")) {
            getUrl = url.substring(0, url.lastIndexOf("?"))
            if (url.length > url.lastIndexOf("?") + 1) {
                urlData = url.substring(url.lastIndexOf("?") + 1)
            }
        }

        if (getData.containsKey(getUrl)) {
            getData[getUrl]?.get(AcceptWeb(getUrl, urlData, "", remoteIp, request), sendWeb)
        } else if (getData.containsKey(wildcard)) {
            getData[wildcard]?.get(AcceptWeb(getUrl, urlData, "", remoteIp, request), sendWeb)
        } else {
            var matched = false
            getWildcardAllData.eachControl { k, v ->
                if (getUrl.startsWith(k)) {
                    matched = true
                    v.get(AcceptWeb(getUrl, urlData, "", remoteIp, request), sendWeb)
                    return@eachControl Control.ControlFind.BREAK
                }
                return@eachControl Control.ControlFind.CONTINUE
            }

            if (!matched) sendWeb.send404()
        }
    }

    internal fun runWebPostInstance(url: String, data: String, request: HttpRequest, sendWeb: SendWeb, remoteIp: String) {
        val wildcard = "${url.substring(0, url.lastIndexOf("/") + 1)}*"
        var getUrl = url
        var urlData = ""

        if (url.contains("?")) {
            getUrl = url.substring(0, url.lastIndexOf("?"))
            if (url.length > url.lastIndexOf("?") + 1) {
                urlData = url.substring(url.lastIndexOf("?") + 1)
            }
        }

        if (postData.containsKey(getUrl)) {
            postData[getUrl]!!.post(AcceptWeb(getUrl, urlData, data, remoteIp, request), sendWeb)
        } else if (postData.containsKey(wildcard)) {
            postData[wildcard]!!.post(AcceptWeb(getUrl, urlData, data, remoteIp, request), sendWeb)
        } else {
            var matched = false
            postWildcardAllData.eachControl { k, v ->
                if (getUrl.startsWith(k)) {
                    matched = true
                    v.post(AcceptWeb(getUrl, urlData, data, remoteIp, request), sendWeb)
                    return@eachControl Control.ControlFind.BREAK
                }
                return@eachControl Control.ControlFind.CONTINUE
            }

            if (!matched) sendWeb.send404()
        }
    }

    internal fun runWebSocketInstance(url: String): StartWebSocket? {
        if (webSocketData.containsKey(url)) {
            return webSocketData[url]!!
        }
        return null
    }

    companion object {
        const val WS_URI = "/WebSocket"
    }
}
