/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import com.corrodinggames.rts.gameFramework.l as GameCore
import net.rwhps.server.util.inline.findField
import java.util.ArrayList

internal object StartModeSurvivalMissionAccessSupport {
    fun gameEngineOrNull(): GameCore? {
        return runCatching { GameCore.B() }.getOrNull()
    }

    fun getBooleanField(target: Any, name: String): Boolean {
        return target.javaClass.findField(name)?.getBoolean(target) ?: false
    }

    fun getIntField(target: Any, name: String): Int {
        return target.javaClass.findField(name)?.getInt(target) ?: 0
    }

    fun getFloatField(target: Any, name: String): Float {
        return target.javaClass.findField(name)?.getFloat(target) ?: 0f
    }

    fun setFloatField(target: Any, name: String, value: Float) {
        target.javaClass.findField(name)?.setFloat(target, value)
    }

    fun setBooleanField(target: Any, name: String, value: Boolean) {
        target.javaClass.findField(name)?.setBoolean(target, value)
    }

    fun getListSize(target: Any, name: String): Int {
        val value = target.javaClass.findField(name)?.get(target)
        return when (value) {
            is Collection<*> -> value.size
            is ArrayList<*> -> value.size
            else -> 0
        }
    }
}
