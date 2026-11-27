/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelOption
import io.netty.channel.Channel
import io.netty.channel.EventLoopGroup
import io.netty.channel.ServerChannel
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollServerSocketChannel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import net.rwhps.server.data.global.Cache
import net.rwhps.server.data.global.Data
import net.rwhps.server.data.global.NetStaticData
import net.rwhps.server.data.bean.ServerConfigRepository
import net.rwhps.server.game.manage.IRwHpsManage
import net.rwhps.server.net.core.AbstractNet
import net.rwhps.server.net.core.IRwHps
import net.rwhps.server.net.core.IRwHps.NetType.*
import net.rwhps.server.net.core.web.AbstractNetWeb
import net.rwhps.server.net.handler.tcp.StartGameNetTcp
import net.rwhps.server.net.handler.tcp.StartHttp
import net.rwhps.server.net.handler.tcp.StartMixProtocol
import net.rwhps.server.net.handler.tcp.StartRemoteControl
import net.rwhps.server.net.http.WebData
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.util.concurrent.threads.GetNewThreadPool.getEventLoopGroup
import net.rwhps.server.util.internal.net.rudp.ReliableServerSocket
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.log.Log.clog
import net.rwhps.server.util.log.Log.error
import net.rwhps.server.util.math.RandomUtils
import java.net.BindException


/**
 * NetGameServer Service
 * Open interfaces at least to the outside world, and try to integrate internally as much as possible
 *
 * @author Dr (dr@der.kim)
 */
class NetService {
    private val closeSeq = Seq<() -> Unit>(1)
    private val start: AbstractNet
    @Volatile
    private var boundPorts: Set<Int> = emptySet()
    private var errorIgnore = false

    val netType: NetTypeEnum
    val id: String

    /** 工作线程数 */
    var workThreadCount = 0

    constructor(id: String, rwHps: IRwHps) {
        require(rwHps.netType != NullProtocol) {
            "No find Protocol"
        }

        val abstractNet =
            if (Data.config.mixProtocolEnable) {
                val map = ObjectMap<IRwHps.NetType, IRwHps>().apply {
                    put(GameProtocol, rwHps)
                    put(HttpProtocol, Cache.iRwHpsCache["MixHttp", { IRwHpsManage.addIRwHps(HttpProtocol, "MixHttpDef") }])
                }
                StartMixProtocol(map)
            } else {
                if (NetStaticData.checkProtocolIsServer(rwHps.netType)) {
                    StartGameNetTcp(rwHps)
                } else {
                    when (rwHps.netType) {
                        DedicatedToTheBackend -> StartGameNetTcp(rwHps)
                        HttpProtocol -> StartHttp(rwHps)
                        RemoteControlProtocol -> StartRemoteControl(rwHps)
                        // D-06: 原为 TODO()（抛 NotImplementedError，catch(Exception) 抓不到）。
                        // 改为显式参数异常，便于调用方诊断与捕获。
                        else -> throw IllegalArgumentException(
                            "Unsupported IRwHps.NetType for NetService: ${rwHps.netType}. " +
                                "Supported: DedicatedToTheBackend, HttpProtocol, RemoteControlProtocol, " +
                                "ServerProtocol/ServerProtocolOld/ServerTestProtocol."
                        )
                    }

                }
        }

        this.netType = when (abstractNet) {
            is StartGameNetTcp -> NetTypeEnum.HeadlessNet
            is StartMixProtocol -> NetTypeEnum.MixTlsAndGame
            is StartRemoteControl -> NetTypeEnum.RemoteControl
            is StartHttp -> NetTypeEnum.HTTPNet
            is AbstractNetWeb -> NetTypeEnum.HTTPNet
            else -> NetTypeEnum.Other
        }

        this.id = id
        this.start = abstractNet

        if (abstractNet is AbstractNetWeb) {
            setWebData()
        }
    }

    init {
        NetStaticData.netService.add(this)
    }

    fun setWebData(data: WebData = Data.webData): NetService {
        if (start is AbstractNetWeb) {
            start.setWebData(data)
        }
        return this
    }

    /**
     * Start the Game Server on the specified port
     * @param port Port
     */
    fun openPort(port: Int) {
        openPort(port, 1, 0)
    }

    /**
     * Start the Game Server in the specified port range
     *
     * @param port MainPort
     * @param startPort Start Port
     * @param endPort End Port
     */
    fun openPort(port: Int, startPort: Int, endPort: Int) {
        Data.config.runPid = net.rwhps.server.util.SystemUtils.pid
        ServerConfigRepository.save(Data.config, Data.configServer, Data.configStartMode)

        clog(Data.i18NBundle.getinput("server.start.open"))
        val bossGroup: EventLoopGroup = getEventLoopGroup(1)
        val workerGroup: EventLoopGroup = getEventLoopGroup(resolvedWorkerThreadCount())

        val runClass: Class<out ServerChannel> = if (Epoll.isAvailable()) {
            EpollServerSocketChannel::class.java
        } else {
            NioServerSocketChannel::class.java
        }

        if (workerGroup is NioEventLoopGroup) {
            workerGroup.setIoRatio(Data.configNet.nettyIoRatio)
        } else if (workerGroup is EpollEventLoopGroup) {
            workerGroup.setIoRatio(Data.configNet.nettyIoRatio)
        }

        val boundChannels = mutableListOf<Channel>()
        try {
            val serverBootstrapTcp = ServerBootstrap()
            serverBootstrapTcp.group(bossGroup, workerGroup).channel(runClass)

            with (serverBootstrapTcp) {
                option(ChannelOption.SO_BACKLOG, 1024)
                childOption(ChannelOption.TCP_NODELAY, true)
                childOption(ChannelOption.SO_KEEPALIVE, true)
                childOption(ChannelOption.ALLOW_HALF_CLOSURE, false)
            }

            serverBootstrapTcp.childHandler(start)

            clog(Data.i18NBundle.getinput("server.start.openPort"))

            val primaryChannel = NetServiceBindSupport.awaitSuccessfulBind(serverBootstrapTcp.bind(port))
            boundChannels += primaryChannel
            for (i in startPort .. endPort) {
                boundChannels += NetServiceBindSupport.awaitSuccessfulBind(serverBootstrapTcp.bind(i))
            }

            boundPorts = (listOf(port) + (startPort .. endPort).toList()).toSet()
            closeSeq.add {
                boundPorts = emptySet()
                boundChannels.forEach { it.close() }
                bossGroup.shutdownGracefully()
                workerGroup.shutdownGracefully()
            }
            clog(Data.i18NBundle.getinput("server.start.end"))

            /*
             * No Fix DeadLock :(
             * io.netty.util.concurrent.DefaultPromise.await(DefaultPromise.java:253)
             */
            primaryChannel.closeFuture().sync()
        } catch (e: InterruptedException) {
            if (!errorIgnore) error("[TCP Start Error]", e)
        } catch (bindError: BindException) {
            if (!errorIgnore) error("[Port Bind Error]", bindError)
        } catch (e: Exception) {
            if (!errorIgnore) {
                if (NetServiceBindSupport.isBindFailure(e)) {
                    error("[Port Bind Error]", e)
                } else {
                    error("[NET Error]", e)
                }
            }
        } finally {
            boundPorts = emptySet()
            boundChannels.forEach { it.close() }
            bossGroup.shutdownGracefully()
            workerGroup.shutdownGracefully()
        }
    }

    fun isPortBound(port: Int): Boolean = boundPorts.contains(port)

    /**
     * Starts the game server on the given port over the RUDP (reliable UDP) transport.
     *
     * D-10: this entry point has **no caller** in the current codebase -- the CNKD NEXT
     * start-mode server only listens over TCP (`StartGameNetTcp`). The RUDP stack is
     * therefore an unwired fallback transport. It is kept because upstream RW-HPS Relay
     * modes use it, and its latent defects were fixed (D-05/D-07/D-09).
     *
     * Do not wire this up without a full reliability review: it will pull in the
     * untouched retransmission machinery and a second accept loop.
     *
     * @param port MainPort
     */
    fun openPortRUDP(port: Int) {
        Log.warn("[RUDP] openPortRUDP() is being used, but the RUDP transport is not wired into the start-mode server. Port=$port")
        clog(Data.i18NBundle.getinput("server.start.open"))
        try {
            ReliableServerSocket(port).use {
                closeSeq.add {
                    it.close()
                }
                while (!it.isClosed) {
                    it.accept()
                }
            }
        } catch (e: InterruptedException) {
            if (!errorIgnore) error("[TCP Start Error]", e)
        } catch (bindError: BindException) {
            if (!errorIgnore) error("[Port Bind Error]", bindError)
        } catch (e: Exception) {
            if (!errorIgnore) error("[NET Error]", e)
        }
    }

    /**
     * Get the number of connections
     * @return Int
     */
    fun getConnectSize(): Int {
        return start.getConnectSize()
    }

    fun stop() {
        errorIgnore = true
        closeSeq.eachAll {
            it()
        }
        NetStaticData.netService.remove(this)
        errorIgnore = false
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (javaClass != other?.javaClass) {
            return false
        }
        if (other is NetService) {
            return id == other.id
        }
        return false
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }


    companion object {
        const val minLowWaterMark = 512 * 1024

        /** Maximum accepted single package size */
        const val maxPacketSizt = 50000000

        /** Packet header data length */
        const val headerSize = 8

        fun coreID(): String {
            return RandomUtils.getRandomString(10)
        }

        fun recommendedRelayWorkerThreadCount(): Int {
            val cpu = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            return (cpu * 2).coerceIn(2, 12)
        }

        enum class NetTypeEnum {
            HeadlessNet,
            RemoteControl,
            HTTPNet,
            MixTlsAndGame,
            Other
        }
    }

    private fun resolvedWorkerThreadCount(): Int {
        if (workThreadCount > 0) {
            return workThreadCount
        }

        if (netType == NetTypeEnum.HeadlessNet) {
            val singleRoomLowLatencyWorkerThreads = runCatching {
                val lowLatencyConfig = Data.configServer.singleRoomLowLatency
                if (lowLatencyConfig.enabled) {
                    lowLatencyConfig.nettyWorkerThreadsOverride.coerceIn(0, 8)
                } else {
                    0
                }
            }.getOrDefault(0)
            if (singleRoomLowLatencyWorkerThreads > 0) {
                return singleRoomLowLatencyWorkerThreads
            }
        }

        return when (netType) {
            NetTypeEnum.HeadlessNet -> {
                if (Data.pureStartMode && Data.configServer.maxPlayer <= 24) 1 else 2
            }
            NetTypeEnum.HTTPNet, NetTypeEnum.RemoteControl -> 1
            NetTypeEnum.MixTlsAndGame -> 2
            NetTypeEnum.Other -> 2
        }
    }
}
