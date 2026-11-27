/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.console

import net.rwhps.server.data.global.Data
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.console.tab.TabDefaultEnum
import net.rwhps.server.util.game.command.CommandHandler.Command
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReader
import org.jline.reader.ParsedLine

/**
 *
 *
 * @date 2023/12/10 17:26
 * @author Dr (dr@der.kim)
 */
class TabCompleter : Completer {
    override fun complete(reader: LineReader, line: ParsedLine, candidates: MutableList<Candidate>) {
        val inCmd = line.line().substring(0, line.cursor()).split(' ')
        val cmd = inCmd[0]
        when (inCmd.size) {
            1 -> {
                val candidatesList = Seq<String>()
                Data.SERVER_COMMAND.commandList.eachAll {
                    if (it.text.startsWith(cmd, ignoreCase = true)) {
                        if (it.text == cmd) {
                            return@eachAll
                        }
                        candidatesList.add(it.text)
                    }
                }
                candidates += candidatesList.map {
                    Candidate(it)
                }
            }
            else -> {
                val waitCompleter = inCmd[inCmd.size - 1]
                getCommandObject(cmd)?.let {
                    if (it.params.size >= inCmd.size - 2) {
                        TabDefaultEnum.from(it.params[inCmd.size - 2].name)?.let {
                            it.tabCompletion.complete(waitCompleter, candidates)
                        }
                    }
                }
            }
        }

    }

    private fun getCommandObject(cmdName: String): Command? {
        return Data.SERVER_COMMAND.commandList.find { it.text == cmdName }
    }
}