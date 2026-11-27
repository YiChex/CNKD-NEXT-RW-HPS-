/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.core.link

import com.corrodinggames.rts.game.n
import com.corrodinggames.rts.gameFramework.j.CustomServerSocket
import com.corrodinggames.rts.gameFramework.j.ad
import com.corrodinggames.rts.gameFramework.j.ai
import com.corrodinggames.rts.gameFramework.j.c
import net.rwhps.server.data.bean.BeanServerConfigFile
import net.rwhps.server.data.bean.ServerConfigRepository
import net.rwhps.server.data.global.Data
import net.rwhps.server.dependent.redirections.game.clas.GameLoopOperationDrain
import net.rwhps.server.game.GameMaps
import net.rwhps.server.game.event.game.ServerHessStartPort
import net.rwhps.server.game.headless.core.link.AbstractLinkGameNet
import net.rwhps.server.plugin.internal.headless.inject.core.GameEngine
import net.rwhps.server.plugin.internal.headless.support.StartModeGameOptionSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeMapLockSupport
import net.rwhps.server.util.inline.findField
import net.rwhps.server.util.log.Log
import java.io.IOException
import com.corrodinggames.rts.gameFramework.j.ao as ServerAcceptRunnable

/**
 * @author Dr (dr@der.kim)
 */
internal class LinkGameNet: AbstractLinkGameNet {
    private var lastUsePort = 0
    private var lastUsePasswd: String? = ""

    override fun newConnect(ip: String, name: String) {
        try {
            //val settingsEngine = GameEngine.settingsEngine
            val netEngine = GameEngine.netEngine

            //settingsEngine.lastNetworkPlayerName = name

            //val playerName = settingsEngine.lastNetworkPlayerName

            netEngine.y = name
            val kVar2 = ad.b(ip, false)
            netEngine.a(kVar2)
            val it: Iterator<*> = netEngine.aM.iterator()
            while (it.hasNext()) {
                (it.next() as c).i = true
            }
        } catch (e2: IOException) {
            Log.error("[GameCore] NewConnect Error", e2)
        }
    }

    override fun startHeadlessServer(port: Int, passwd: String?): Boolean {
        var started = false
        GameLoopOperationDrain.resetHealth()
        val netEngine = GameEngine.netEngine

        GameEngine.settingsEngine.apply {
//            setValueDynamic("networkPort", port.toString())
//            setValueDynamic("udpInMultiplayer", false.toString())
//            setValueDynamic("saveMultiplayerReplays", )
            networkPort = port
            udpInMultiplayer = false
            saveMultiplayerReplays = Data.configServer.saveRePlayFile
        }

        netEngine.m = port
        netEngine.y = Data.headlessName

        if (Data.neverEnd) {
            netEngine.s = true
        }

        GameEngine.data.room.run {
            roomID = "Port: $port"
            lastUsePort = port
            lastUsePasswd = passwd

            try {
                GameEngine.root.hostStartWithPasswordAndMods(false, passwd, true)

                // P1 root-cause fix for "game frame:N is greater then nest step:M".
                //
                // The engine's host-start path (ad.b(Z)Z -> q() -> a(Z) -> s()) resets the network
                // step counter ad.X to 0 but never clears the game frame counter l.bx. The engine's
                // own symmetric reset lives in ad.aD()V (pc=42 l.bx=0, pc=47 l.by=0), which is only
                // reached from ad.a(F)V pc=138 and is gated off by ad.aY (ad.s() pc=119 sets
                // aY=false). So a headless reboot at game over leaves l.bx at the previous match's
                // final frame (e.g. 4584) while ad.X becomes 0; the next advancing tick computes
                // ad.X = 0 + Q and ad.a(FZ)V immediately throws because l.bx > ad.X.
                //
                // Mirror ad.aD()V here, immediately after host start and before anything else can
                // observe the stale value. Both fields are public int and are already read directly
                // by GameHessData.kt:35-36, so no reflection or ASM injection is required.
                // Idempotent on first start: l.<init> already leaves bx=0 and by defaults to 0.
                GameEngine.gameEngine.bx = 0
                GameEngine.gameEngine.by = 0

                StartModeGameOptionSupport.applyConfiguredLobbyDefaults(GameEngine.data)
                StartModeMapLockSupport.applyLockedMapDuringBootstrap()

                // 设置新的监听
                val tcpRunnable = CustomServerSocket(GameEngine.netEngine)
                tcpRunnable.a(false)

                // SocketServer
                GameEngine.netEngine::class.java.findField("aE", ServerAcceptRunnable::class.java)!!
                    .set(GameEngine.netEngine, tcpRunnable)
                // 通过特性来干掉自带的 HOST
                // 新建一个 Player[]
                n.F()
                // 进行扩展
                val runtimeMaxPlayer = Data.configServer.maxPlayer.coerceIn(1, BeanServerConfigFile.GAME_MAX_PLAYER_SIZE)
                if (runtimeMaxPlayer != Data.configServer.maxPlayer) {
                    Log.warn(
                        "[StartMode] gameplay.maxPlayer=${Data.configServer.maxPlayer} is outside the game core limit. " +
                                "Using $runtimeMaxPlayer and writing the safe value back to ServerConfig.json."
                    )
                    Data.configServer.coverField("maxPlayer", runtimeMaxPlayer)
                    ServerConfigRepository.save(Data.config, Data.configServer, Data.configStartMode)
                }
                n.b(runtimeMaxPlayer, true)
                // 避免同步爆炸, 给自身设置一个隐藏队伍
                GameEngine.netEngine.z = n.k(-1)

                // 不能在启动前设置, 因为每次会变
                // 设置客户端UUID, 避免Admin/ban等不能持久化
                GameEngine.settingsEngine.setValueDynamic("networkServerId", Data.core.serverConnectUuid)

                val startEvent = GameEngine.data.eventManage.fire(ServerHessStartPort(GameEngine.data))
                startEvent.await()
                startEvent.cause()?.let { error ->
                    throw IllegalStateException("Server startup event failed", error)
                }

                if (Data.neverEnd) {
                    val mapPlayer = "Valley Arena (10p) [by_uber]@[z;p10]"
                    val data = mapPlayer.split("@").toTypedArray()
                    GameEngine.netEngine.az = "maps/skirmish/${data[1]}${data[0]}.tmx"
                    GameEngine.netEngine.ay.a = ai.a
                    GameEngine.netEngine.ay.b = "${data[1]}${data[0]}.tmx"
                    GameEngine.data.room.maps.mapName = data[0]
                    GameEngine.data.room.maps.mapType = GameMaps.MapType.DefaultMap
                    StartModeGameOptionSupport.applyEffectiveNoNukesBeforeStart(GameEngine.data)
                    GameEngine.data.gameScriptMultiPlayer.multiplayerStart()
                }

                // Open the custom listener only after room defaults and startup handlers are ready.
                GameEngine.netEngine::class.java.findField("aD", Thread::class.java)!!
                    .set(GameEngine.netEngine, Thread(tcpRunnable).apply { start() })
                started = true
            } catch (error: Throwable) {
                Log.error("[StartMode] Failed to start headless server on port $port", error)
                val partialCleanupSucceeded = runCatching { closeHeadlessServer() }
                    .getOrElse { cleanupError ->
                        Log.warn("[StartMode] Failed to clean partial headless startup on port $port.", cleanupError)
                        false
                    }
                if (!partialCleanupSucceeded) {
                    Log.warn("[StartMode] Partial headless startup cleanup was incomplete on port $port.")
                }
            }
        }
        return started
    }

    override fun closeHeadlessServer(): Boolean {
        var closed = true
        runCatching { GameEngine.data.room.call.killAllPlayer() }
            .onFailure {
                closed = false
                Log.warn("[StartMode] Failed to kick players while closing headless server.", it)
            }

        // 恢复
        runCatching { GameEngine.netEngine.z = null }
            .onFailure {
                closed = false
                Log.warn("[StartMode] Failed to clear hidden host player while closing headless server.", it)
            }

        runCatching { GameEngine.root.multiplayer.disconnect("Close Server") }
            .onFailure {
                closed = false
                Log.warn("[StartMode] Failed to disconnect old headless server; continuing reboot cleanup.", it)
            }
        return closed
    }

    override fun reBootServer(run: () -> Unit): Boolean {
        if (lastUsePort == 0) {
            val cleanupSucceeded = runCatching { run() }
                .onFailure { Log.warn("[StartMode] Failed to run reboot cleanup before first headless start.", it) }
                .isSuccess
            val startSucceeded = startHeadlessServer()
            return cleanupSucceeded && startSucceeded
        } else {
            val port = lastUsePort
            val passwd = lastUsePasswd
            val closeSucceeded = runCatching { closeHeadlessServer() }
                .getOrElse {
                    Log.warn("[StartMode] Failed to close old headless server before reboot; cleanup will continue.", it)
                    false
                }
            val cleanupSucceeded = runCatching { run() }
                .onFailure { Log.warn("[StartMode] Failed to run reboot cleanup after closing old headless server.", it) }
                .isSuccess
            val startSucceeded = startHeadlessServer(port, passwd)
            return closeSucceeded && cleanupSucceeded && startSucceeded
        }
    }
}
