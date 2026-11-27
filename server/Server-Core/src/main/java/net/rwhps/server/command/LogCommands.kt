/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */
package net.rwhps.server.command

import net.rwhps.server.data.global.Data
import net.rwhps.server.data.global.NetStaticData
import net.rwhps.server.func.StrCons
import net.rwhps.server.game.room.RelayRoom.Companion.getRelay
import net.rwhps.server.game.room.RelayRoom.Companion.relayAllIP
import net.rwhps.server.game.room.RelayRoom.Companion.sendAllMsg
import net.rwhps.server.net.NetService
import net.rwhps.server.net.core.server.AbstractNetConnect
import net.rwhps.server.util.IpUtils.longToIp
import net.rwhps.server.util.IsUtils.isBlank
import net.rwhps.server.util.IsUtils.isNull
import net.rwhps.server.util.annotations.mark.PrivateMark
import net.rwhps.server.util.file.FileUtils.Companion.getFolder
import net.rwhps.server.util.game.command.CommandHandler
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author Dr (dr@der.kim)
 */
class LogCommands @PrivateMark constructor(handler: CommandHandler) {
}