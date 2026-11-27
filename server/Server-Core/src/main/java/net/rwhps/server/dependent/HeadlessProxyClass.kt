/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.dependent

import net.rwhps.asm.agent.AsmAgent
import net.rwhps.server.dependent.redirections.game.GameMainRedirections
import net.rwhps.server.dependent.redirections.lwjgl.LwjglRedirections
import net.rwhps.server.dependent.redirections.slick.SlickRedirections
import net.rwhps.server.util.annotations.mark.AsmMark

/**
 * What could it be
 */
/**
 * 注册 Hess 的 ASM
 *
 * @author RW-HPS
 */
@AsmMark.ClassLoaderCompatible
class HeadlessProxyClass: AgentAttachData() {
    init {
        initProxyClass()
    }

    private fun initProxyClass() {
        /* Register headless Lwjgl */
        LwjglRedirections().register()
        /* Register headless Slick */
        SlickRedirections().register()
        /* Game */
        GameMainRedirections().register()


        AsmAgent.agentmain(this.instrumentation)
    }
}