/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.dependent

import net.rwhps.server.dependent.hot.DynamicByteClassLoader
import net.rwhps.server.util.annotations.DidNotFinish
import java.lang.instrument.ClassDefinition

/**
 * 提供热加载
 *
 * @author Dr (dr@der.kim)
 */
@DidNotFinish
internal class HotLoadClass: AgentAttachData() {
    fun load(bytes: ByteArray) {
        val targetClazz = DynamicByteClassLoader(bytes).findClass()
        //System.err.println("目标class类全路径为" + targetClazz!!.name)
        val clazzDef = ClassDefinition(Class.forName(targetClazz!!.name), bytes)
        instrumentation.redefineClasses(clazzDef)
    }
}