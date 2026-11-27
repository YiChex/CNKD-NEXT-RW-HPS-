/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.dependent.redirections.game

import net.rwhps.asm.data.MethodTypeInfoValue
import net.rwhps.server.dependent.redirections.MainRedirections
import net.rwhps.server.util.annotations.mark.AsmMark
import net.rwhps.server.util.annotations.mark.GameSimulationLayer
import net.rwhps.server.util.inline.findField
import net.rwhps.server.util.inline.toClassAutoLoader
import net.rwhps.server.util.log.Log

//关闭傻逼格式化
//@formatter:off

/**
 * @author Dr (dr@der.kim)
 */
@AsmMark.ClassLoaderCompatible
class CustomRedirections: MainRedirections {
    override fun register() {
        /* 屏蔽游戏错误捕获 */
        @GameSimulationLayer.GameSimulationLayer_KeyWords("setDefaultUncaughtExceptionHandler")
        redirectR(MethodTypeInfoValue("com/corrodinggames/rts/gameFramework/l", "aq", "()V"))

        /* MOD启用器覆盖(强制启用除自带以外) */
        @GameSimulationLayer.GameSimulationLayer_KeyWords("Loading mod selection")
        redirectR(MethodTypeInfoValue("com/corrodinggames/rts/gameFramework/i/a", "f", "()V")) { obj: Any, _: String, _: Class<*>, _: Array<out Any?> ->
            Log.debug("[Hess] Try Loading mod selection")
            val modDataClass = "com.corrodinggames.rts.gameFramework.i.b".toClassAutoLoader(obj)!!

            val modNameField = modDataClass.findField("c", String::class.java)!!
            val modEnableField = modDataClass.findField("f", Boolean::class.javaPrimitiveType)!!

            ("com.corrodinggames.rts.gameFramework.i.a".toClassAutoLoader(obj)!!.findField("e", ArrayList::class.java)!!.get(obj) as ArrayList<*>).forEach {
                // 过滤原版单位
                if (modNameField[it] != "mega_builders") {
                    modEnableField.setBoolean(it, false)
                }
            }
        }
    }
}
