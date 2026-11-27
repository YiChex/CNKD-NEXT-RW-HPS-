/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.core

import com.corrodinggames.librocket.b
import com.corrodinggames.librocket.scripts.Multiplayer
import com.corrodinggames.librocket.scripts.Root
import com.corrodinggames.librocket.scripts.ScriptContext
import com.corrodinggames.rts.gameFramework.j.ad
import com.corrodinggames.rts.gameFramework.l
import com.corrodinggames.rts.java.Main
import net.rwhps.server.core.ServiceLoader
import net.rwhps.server.game.event.EventManage
import net.rwhps.server.game.headless.core.*
import net.rwhps.server.game.headless.core.link.AbstractLinkGameFunction
import net.rwhps.server.game.headless.core.link.AbstractLinkGameNet
import net.rwhps.server.game.headless.core.link.AbstractLinkGameServerData
import net.rwhps.server.game.headless.core.scripts.AbstractScriptMultiPlayer
import net.rwhps.server.game.headless.core.scripts.AbstractScriptRoot
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.net.core.IRwHps
import net.rwhps.server.plugin.internal.headless.HessMain
import net.rwhps.server.plugin.internal.headless.inject.command.ClientCommands
import net.rwhps.server.plugin.internal.headless.inject.command.ServerCommands
import net.rwhps.server.plugin.internal.headless.inject.core.link.LinkGameFunction
import net.rwhps.server.plugin.internal.headless.inject.core.link.LinkGameNet
import net.rwhps.server.plugin.internal.headless.inject.core.link.LinkGameServerData
import net.rwhps.server.plugin.internal.headless.inject.core.scripts.ScriptMultiPlayer
import net.rwhps.server.plugin.internal.headless.inject.core.scripts.ScriptRoot
import net.rwhps.server.plugin.internal.headless.inject.net.GameVersionServer
import net.rwhps.server.plugin.internal.headless.inject.net.HeadlessTypeConnect
import net.rwhps.server.util.inline.accessibleConstructor
import net.rwhps.server.util.inline.findField
import net.rwhps.server.util.inline.toClass
import com.corrodinggames.rts.java.b as RWAppGameContainer

/**
 * RW-HPS 的 Hess 实现内部接口
 * 内部实现通过 [GameEngine] 来减少一些混淆 也可以参见 (RW-AC项目)
 *
 * @author Dr (dr@der.kim)
 */
internal object GameEngine {
    lateinit var data: AbstractGameModule
        private set
    val statisticsModuleOrNull: AbstractGameModule?
        get() = if (::data.isInitialized) data else null

    /** 设置脚本加载器, 并完成初始化 */
    val root by lazy {
        Root().apply {
        // 设置对应的 Multiplayer
        this.multiplayer = Multiplayer::class.java.accessibleConstructor(Root::class.java).newInstance(this)

        // 初始化 ScriptContext 的 libRocket
        ScriptContext::class.java.findField("libRocket")!!.set(this, object: b() {
            override fun EnableScissorRegion(p0: Boolean) {
                // ignore
            }
        })
        }
    }

    val gameEngine: l get() = l.B()
    val mapEngine: com.corrodinggames.rts.game.b.b get() = gameEngine.bL
    val mapEngineOrNull: com.corrodinggames.rts.game.b.b?
        get() = runCatching { gameEngine.bL }.getOrNull()
    val netEngine: ad get() = gameEngine.bX

    fun isMapEngineReady(): Boolean {
        return runCatching {
            val mapEngine = gameEngine.bL
            mapEngine != null && mapEngine.W
        }.getOrDefault(false)
    }

    val settingsEngine get() = gameEngine.bQ!!

    val gameStatistics get() = gameEngine.bY!!

    val mainObject by lazy {
        val mainClass = "com.corrodinggames.rts.java.Main".toClass(data.useClassLoader)!!
        mainClass.findField("m", mainClass)!!.get(null) as Main
    }

    val appGameContainerObject by lazy {
        val mainClass = "com.corrodinggames.rts.java.Main".toClass(data.useClassLoader)!!
        val appGameContainerClass = "com.corrodinggames.rts.java.b".toClass(data.useClassLoader)!!
        mainClass.findField("k", appGameContainerClass)!!.get(mainObject) as RWAppGameContainer
    }

    var iRwHps: IRwHps? = null

    /**
     * 通过这里完成 [AbstractGameModule] 通用接口实现
     */
    @JvmStatic
    fun init() {
        val loader = GameEngine.javaClass.classLoader
        HeadlessModuleManage.addGameModule(loader.toString(), object: AbstractGameModule {
            override val useClassLoader: ClassLoader = loader
            override val eventManage: EventManage = EventManage()

            override val gameData: AbstractGameData = GameData()

            override val gameFast: AbstractGameFast = GameFast()
            override val gameHessData: AbstractGameHessData = GameHessData()
            override val gameUnitData: AbstractGameUnitData = GameUnitData()
            override val gameFunction: AbstractGameFunction = GameFunction()

            override val gameScriptMultiPlayer: AbstractScriptMultiPlayer = ScriptMultiPlayer()
            override val gameScriptRoot: AbstractScriptRoot = ScriptRoot()

            override val gameLinkFunction: AbstractLinkGameFunction = LinkGameFunction()
            override val gameLinkServerData: AbstractLinkGameServerData = LinkGameServerData()
            override val gameLinkNet: AbstractLinkGameNet = LinkGameNet()

            override val room: ServerRoom = ServerRoom(this)
        }.also {
            data = it
        })

        ServiceLoader.addService(ServiceLoader.ServiceType.ProtocolType, IRwHps.NetType.ServerProtocol.name, HeadlessTypeConnect::class.java)
        ServiceLoader.addService(ServiceLoader.ServiceType.Protocol, IRwHps.NetType.ServerProtocol.name, GameVersionServer::class.java)

        /* Register Server Protocol Command */
        ServerCommands(HessMain.serverServerCommands)
        ClientCommands(data.room.clientHandler)
    }
}
