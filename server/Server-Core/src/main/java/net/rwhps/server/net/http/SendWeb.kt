/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.http

import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.handler.codec.http.*
import io.netty.util.AsciiString
import net.rwhps.server.data.global.Data
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.util.inline.mutableObjectMapOf

/**
 * @author Dr (dr@der.kim)
 */
class SendWeb(
    private val channel: Channel, private val request: HttpRequest
) {
    private var cacheData: ByteArray? = null

    /** HTTP 状态 */
    var status: HttpResponseStatus = HttpResponseStatus.OK

    /** 覆盖原header */
    private val replaceHeaders: ObjectMap<AsciiString, String> = mutableObjectMapOf(/* 默认头 使用 RW-HPS 自定义 */
            HttpHeaderNames.SERVER to "RW-HPS/${Data.SERVER_CORE_VERSION} (WebData)",
            HttpHeaderNames.CONTENT_TYPE to "${HttpHeaderValues.TEXT_HTML};charset=utf-8",
            HttpHeaderNames.ACCEPT_CHARSET to "UTF-8"
    )

    /** 附加的header,用于需要重复的header */
    private val appendHeaders: ObjectMap<String, ArrayList<String>> = mutableObjectMapOf()

    fun setHead(key: AsciiString, value: String) {
        replaceHeaders[key] = value
    }

    fun customAppendHead(key: String, value: String) {
        appendHeaders[key, { arrayListOf() }].add(value)
    }

    fun setConnectType(type: AsciiString) = setConnectType(type.toString())
    fun setConnectType(type: String) = setHead(HttpHeaderNames.CONTENT_TYPE, "$type;charset=UTF-8")
    fun setCookie(cKey: String, cValue: String, maxAge: Int, path: String) = customAppendHead(
            HttpHeaderNames.SET_COOKIE.toString(), "$cKey=$cValue; Max-Age=$maxAge; Path=$path"
    )


    fun setData(bytes: ByteArray) {
        setConnectType(HttpHeaderValues.MULTIPART_FORM_DATA.toString())
        cacheData = bytes
    }

    fun setData(string: String) {
        cacheData = string.toByteArray(Data.UTF_8)
    }

    fun send() {
        if (cacheData == null) {
            throw NullPointerException("Web Send Data is null")
        }
        val defaultFullHttpResponse = DefaultFullHttpResponse(request.protocolVersion(), status)
        for (header in replaceHeaders) {
            defaultFullHttpResponse.headers()[header.key] = header.value
        }
        for (header in appendHeaders) {
            for (value in header.value) {
                defaultFullHttpResponse.headers().add(header.key, header.value)
            }
        }

        defaultFullHttpResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, cacheData!!.size)
        defaultFullHttpResponse.content().writeBytes(cacheData)

        val lastContentFuture = channel.writeAndFlush(defaultFullHttpResponse)

        //如果不支持keep-Alive，服务器端主动关闭请求
        if (!HttpUtil.isKeepAlive(request)) {
            lastContentFuture.addListener(ChannelFutureListener.CLOSE)
        }
    }

    fun send404(send: Boolean = true) {
        status = HttpResponseStatus.NOT_FOUND
        cacheData = """
        <p>[404] File not found
            <br>The project is based on 
                <strong>RW-HPS</strong>
                <br>
                    <a href="https://github.com/RW-HPS/RW-HPS">RW-HPS Github</a>
                </p>
        """.trimIndent().toByteArray(Data.UTF_8)
        privateBedReq(send)
    }

    fun sendBedRequest(send: Boolean = true) {
        status = HttpResponseStatus.BAD_REQUEST
        cacheData = """
        <p>[400] Can't find a match HOST
            <br>The project is based on 
                <strong>RW-HPS</strong>
                <br>
                    <a href="https://github.com/RW-HPS/RW-HPS">RW-HPS Github</a>
                </p>
        """.trimIndent().toByteArray(Data.UTF_8)
        privateBedReq(send)
    }

    private fun privateBedReq(send: Boolean) {
        setConnectType(HttpHeaderValues.TEXT_HTML)
        if (send) {
            send()
        }
    }
}