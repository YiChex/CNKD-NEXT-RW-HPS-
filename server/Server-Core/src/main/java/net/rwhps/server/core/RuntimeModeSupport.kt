/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.core

import net.rwhps.server.data.global.Data

object RuntimeModeSupport {
    fun isPureStartCommand(command: String): Boolean {
        return command.trim().substringBefore(' ').equals("start", ignoreCase = true)
    }

    fun isPureStartMode(): Boolean {
        return Data.pureStartMode || isPureStartCommand(Data.config.defStartCommand)
    }
}
