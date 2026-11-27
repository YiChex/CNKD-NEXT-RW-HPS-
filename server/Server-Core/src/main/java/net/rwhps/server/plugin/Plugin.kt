/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin

import net.rwhps.server.data.global.Data
import net.rwhps.server.game.event.EventGlobalManage
import net.rwhps.server.game.event.EventManage
import net.rwhps.server.util.annotations.mark.PrivateMark
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.file.load.LoadIni
import net.rwhps.server.util.game.command.CommandHandler
import java.util.*

/**
 *
 * @author Dr (dr@der.kim)
 */
@Suppress("UNUSED")
abstract class Plugin {
    lateinit var pluginDataFileUtils: FileUtils
        internal set

    /**
     * 提供语言注入
     * @param lang String
     * @param kv Properties
     * @return 是否成功
     */
    @JvmOverloads
    fun loadLang(lang: String, ini: LoadIni, cover: Boolean = false): Boolean {
        return Data.i18NBundleMap[lang]?.addLang(ini, cover) == true
    }

    /**
     * 提供语言注入
     * @param lang String
     * @param k String
     * @param v String
     * @return 是否成功
     */
    @JvmOverloads
    fun loadLang(lang: String, k: String, v: String, cover: Boolean = false): Boolean {
        return Data.i18NBundleMap[lang]?.addLang(k, v, cover) == true
    }

    /** 最先执行 可以进行Plugin的数据读取  -1  */
    open fun onEnable() {
        // Plugin inheritance, we should not implement
    }

    /**
     * 注册要在服务器端使用的Core命令，例如从控制台
     * 这里注册的命令无论启动什么协议 都会存在
     */
    open fun registerCoreCommands(handler: CommandHandler) {
        // Plugin inheritance, we should not implement
    }
    /**
     * 注册要在服务器端使用的Server命令，例如从控制台-Server
     * 这里注册的命令只有启动Server协议 才会存在
     */
    open fun registerServerCommands(handler: CommandHandler) {
        // Plugin inheritance, we should not implement
    }
    /**
     * 注册要在服务器端使用的Relay命令，例如从控制台-Relay
     * 这里注册的命令只有启动Relay协议 才会存在
     */
    @PrivateMark
    open fun registerRelayCommands(handler: CommandHandler) {
        // Plugin inheritance, we should not implement
    }


    /**
     * 注册要在客户端使用的任何命令，例如来自游戏内玩家 -3
     *
     * ## 使用
     * 这里不会多次调用
     *
     * 默认只会调用服务器主 Hess 的 Command
     */
    open fun registerServerClientCommands(handler: CommandHandler) {
        // Plugin inheritance, we should not implement
    }

    /** 注册要在客户端使用的RELAY命令，例如来自RELAY内玩家 -3  */
    @PrivateMark
    open fun registerRelayClientCommands(handler: CommandHandler) {
        // Plugin inheritance, we should not implement
    }

    /**
     * 注册无头端事件
     *
     * ## 使用
     * 这里不会多次调用
     *
     * 因为 `RW-HPS` 虽然支持多个 `Hess` 端运行, 但是, 由于多个端将会让Event支持多端, 但我没有精力, 所以只会在 `Main` 端上执行 Event
     *
     * 同样的, 如果需要多个 `Hess` 端注入, 您不应该使用这个方法, 请使用
     * ```
     * object: EventListener {
     *     @EventListenerHandler
     *     fun registerServerHessLoadEvent(serverHessLoadEvent: ServerHessLoadEvent) {
     *         // 需要 Event 支持同时多个Hess运行, 不建议多个 Hess 共用一个Event实例
     *         serverHessLoadEvent.eventManage.registerListener(您的Event())
     *     }
     * }
     * ```
     *
     * 在不同的 `Hess` 端, Event是不共用的, 每一个 `Hess` 端, 就有一个新的 Event 管理器
     *
     * @param eventManage Hess事件管理器
     */
    open fun registerEvents(eventManage: EventManage) {
        // Plugin inheritance, we should not implement
    }

    /**
     * 注册全局事件
     *
     * @param eventManage 全局事件管理器
     */
    open fun registerGlobalEvents(eventManage: EventGlobalManage) {
        // Plugin inheritance, we should not implement
    }

    /** 创建所有插件并注册命令后调用 -5  */
    open fun init() {
        // Plugin inheritance, we should not implement
    }


    /** (注意 将会强制继承) Server退出时执行 可以进行Plugin的数据保存 -6  */
    open fun onDisable() {
        // Plugin inheritance, we should not implement
    }
}