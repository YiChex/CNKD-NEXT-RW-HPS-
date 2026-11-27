/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.handler.tcp

import io.netty.buffer.ByteBuf
import io.netty.channel.*
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.ssl.SslHandler
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.CharsetUtil
import net.rwhps.server.data.global.Data
import net.rwhps.server.net.api.RelayApiSupport
import net.rwhps.server.net.core.AbstractNet
import net.rwhps.server.net.core.IRwHps
import net.rwhps.server.net.core.web.AbstractNetWeb
import net.rwhps.server.net.http.AcceptWeb
import net.rwhps.server.net.http.SendWeb
import net.rwhps.server.net.http.WebData
import net.rwhps.server.net.http.WebData.Companion.WS_URI
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.log.Log
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.Locale
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine


/**
 * @author Dr (dr@der.kim)
 */
@ChannelHandler.Sharable
open class StartHttp(rwHps: IRwHps): AbstractNet(rwHps), AbstractNetWeb {
    private var sslContext: SSLContext? = null
    private lateinit var webData: WebData

    init {
        init()
    }

    override fun setWebData(data: WebData) {
        webData = data
    }

    override fun initChannel(socketChannel: SocketChannel) {
        initChannel(socketChannel, true)
    }

    protected fun initChannel(socketChannel: SocketChannel, ssl: Boolean) {
        if (Data.config.sslEnable && (!Data.config.sslMixEnable || ssl)) {
            if (sslContext == null) {
                sslContext = getSslContext()
            }
            val sslEngine: SSLEngine = sslContext!!.createSSLEngine()
            sslEngine.useClientMode = false
            socketChannel.pipeline().addLast("ssl", SslHandler(sslEngine))
        }
        socketChannel.pipeline().addLast("http", object: ChannelInboundHandlerAdapter() {
            @Throws(Exception::class)
            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                val firstData: ByteBuf = msg as ByteBuf

                val headS: String = firstData.toString(Data.UTF_8)
                if (isHttpReq(headS)) {
                    if (headS.startsWith("GET ${WS_URI}")) {
                        val head = headS.split("\\R")[0].split(" ")[1]
                        ctx.channel().pipeline().addLast(
                                HttpServerCodec(),
                                ChunkedWriteHandler(),
                                HttpObjectAggregator(1048576),
                                WebSocketServerProtocolHandler(head)
                        )
                        ctx.channel().pipeline().addFirst(IdleStateHandler(10, 0, 0), object: ChannelDuplexHandler() {
                            @Throws(Exception::class)
                            override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                                val evt1: IdleStateEvent = evt as IdleStateEvent
                                if (evt1.state() === IdleState.READER_IDLE) {
                                    Log.clog("已经10秒没有读到数据了,主动断开连接" + ctx.channel())
                                    ctx.channel().close()
                                }
                            }
                        })
                        val wsProcessing = webData.runWebSocketInstance(head)
                        if (wsProcessing != null) {
                            ctx.channel().pipeline().addLast(wsProcessing)
                        } else {
                            ctx.close()
                        }
                    } else {
                        ctx.channel().pipeline().addLast(HttpServerCodec(), HttpObjectAggregator(1048576))
                        ctx.channel().pipeline().addLast(object: SimpleChannelInboundHandler<Any>() {
                            @Throws(Exception::class)
                            override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) {
                                if (msg is HttpRequest) {
                                    val request = msg

                                    val send = SendWeb(ctx.channel(), request)
                                    val remoteIp = (ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress
                                        ?: ctx.channel().remoteAddress().toString().removePrefix("/").substringBeforeLast(":")
                                    // 获取请求的域名。Host 头通常会自动带端口，webHOST 配置不带端口时也应视为同一主机。
                                    if (!matchesConfiguredWebHost(Data.config.webHOST, request.headers()[HttpHeaderNames.HOST])) {
                                        send.sendBedRequest()
                                        return
                                    }

                                    val url = request.uri()

                                    if (request.method() == HttpMethod.OPTIONS) {
                                        RelayApiSupport.applyCors(AcceptWeb(url, "", "", remoteIp, request), send)
                                        send.status = HttpResponseStatus.NO_CONTENT
                                        send.setData("")
                                        send.send()
                                        return
                                    } else if (request.method() == HttpMethod.GET) {
                                        webData.runWebGetInstance(url, request, send, remoteIp)
                                        return
                                    } else if (request.method() == HttpMethod.POST) {
                                        if (msg is HttpContent) {
                                            val httpContent = msg as HttpContent
                                            val content = httpContent.content()
                                            val buf = StringBuilder()
                                            buf.append(content.toString(CharsetUtil.UTF_8))
                                            webData.runWebPostInstance(url, buf.toString(), request, send, remoteIp)
                                            return
                                        }
                                    }
                                }
                            }
                        })
                    }
                }
                //我该往哪去呢? 这天就要亮了
                ctx.pipeline().remove("http")
                ctx.fireChannelRead(msg)
            }
        })
    }

    private fun matchesConfiguredWebHost(configuredHost: String, requestHost: String?): Boolean {
        val expected = configuredHost.trim()
        if (expected.isEmpty()) {
            return true
        }

        val actual = requestHost?.trim().orEmpty()
        if (actual == expected) {
            return true
        }
        if (actual.isEmpty() || hasExplicitPort(expected)) {
            return false
        }

        return normalizeHostWithoutOptionalPort(actual) == normalizeHostWithoutOptionalPort(expected)
    }

    private fun normalizeHostWithoutOptionalPort(host: String): String {
        val trimmed = host.trim()
        val normalized = when {
            trimmed.startsWith("[") && trimmed.contains("]") -> trimmed.substringAfter("[").substringBefore("]")
            trimmed.count { it == ':' } == 1 && trimmed.substringAfterLast(":").all { it.isDigit() } -> trimmed.substringBeforeLast(":")
            else -> trimmed
        }
        return normalized.trim().trimEnd('.').lowercase(Locale.ROOT)
    }

    private fun hasExplicitPort(host: String): Boolean {
        val trimmed = host.trim()
        return when {
            trimmed.startsWith("[") && trimmed.contains("]") -> trimmed.substringAfter("]").startsWith(":")
            trimmed.count { it == ':' } == 1 -> trimmed.substringAfterLast(":").all { it.isDigit() }
            else -> false
        }
    }

    private fun getSslContext(protocol: String = "TLSv1.2"): SSLContext {
        val filePass = Data.config.sslPasswd.toCharArray()
        val sslContext: SSLContext = SSLContext.getInstance(protocol)
        val keyStore: KeyStore = KeyStore.getInstance("JKS")
        keyStore.load(FileUtils.getFile("ssl.jks").getInputsStream(), filePass)
        val kmf: KeyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, filePass)
        sslContext.init(kmf.keyManagers, null, null)
        return sslContext
    }

    protected fun isHttpReq(head: String): Boolean {
        return head.startsWith("GET ") || head.startsWith("POST ") || head.startsWith("DELETE ") || head.startsWith(
                "HEAD "
        ) || head.startsWith(
                "PUT "
        ) || head.startsWith(
                "OPTIONS "
        )
    }
}
