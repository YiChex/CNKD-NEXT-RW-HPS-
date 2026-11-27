/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.headless.core

import net.rwhps.server.game.event.EventManage
import net.rwhps.server.game.headless.core.link.AbstractLinkGameFunction
import net.rwhps.server.game.headless.core.link.AbstractLinkGameNet
import net.rwhps.server.game.headless.core.link.AbstractLinkGameServerData
import net.rwhps.server.game.headless.core.scripts.AbstractScriptMultiPlayer
import net.rwhps.server.game.headless.core.scripts.AbstractScriptRoot
import net.rwhps.server.game.room.ServerRoom

/**
 * 通过这里的稳定接口来调用游戏内部实现
 *
 * 实现 服务器调用不同加载器的Headless, 这里作为中间兼容层
 *
 * 禁止耦合, Headless实现不应该依赖内部代码
 *
 * D-25: the KDoc below used to name members that do not exist on this interface
 * (`gameLinkNet` was documented as `AbstractGameNet`, `gameLinkFunction` as
 * `AbstractGameLinkFunction`, `gameLinkServerData` as `AbstractGameLinkData`, and
 * `gameData` / `gameFunction` / `gameScript*` were missing entirely). Each property is
 * now documented after its own declaration so the docs cannot drift again.
 *
 * This interface is a pure contract: it has no lifecycle methods. Lifecycle entry points
 * live on [AbstractLinkGameNet] (start/close/reboot) and [AbstractLinkGameFunction]
 * (sync / battleRoom / saveGame / clean / pauseGame).
 */
interface AbstractGameModule {
    /** ClassLoader that owns the headless (game) implementation of this contract. */
    val useClassLoader: ClassLoader

    /** Room-scoped event manager of the Hess instance. */
    val eventManage: EventManage

    /** Injected command packet list and related game data. */
    val gameData: AbstractGameData

    /** Outgoing packet filter. */
    val gameFast: AbstractGameFast

    /** Tick / FPS / game-over / AI-server accessors. */
    val gameHessData: AbstractGameHessData

    /** Unit data and mod unit tables. */
    val gameUnitData: AbstractGameUnitData

    /** Main-thread operation dispatch. */
    val gameFunction: AbstractGameFunction

    /** Script-layer multiplayer hooks. */
    val gameScriptMultiPlayer: AbstractScriptMultiPlayer

    /** Script-layer root hooks. */
    val gameScriptRoot: AbstractScriptRoot

    /** Full-sync / lobby-sync / battle-room / save helpers. */
    val gameLinkFunction: AbstractLinkGameFunction

    /** Room settings and player tables mirrored from the game engine. */
    val gameLinkServerData: AbstractLinkGameServerData

    /** Network control channel: startHeadlessServer / closeHeadlessServer / reBootServer / newConnect. */
    val gameLinkNet: AbstractLinkGameNet

    /** The server room bound to this Hess instance. */
    val room: ServerRoom
}