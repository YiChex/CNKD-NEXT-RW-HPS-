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
import net.rwhps.server.dependent.redirections.game.clas.CompatibleThreadUnsafeArrayList
import net.rwhps.server.dependent.redirections.game.clas.GameLoopOperationDrain
import net.rwhps.server.dependent.redirections.game.clas.RenderGateGuard
import net.rwhps.server.util.annotations.mark.GameSimulationLayer

/**
 *
 *
 * @date 2024/1/15 20:14
 * @author Dr (dr@der.kim)
 */
class ValueLinkRedirections: MainRedirections {
    override fun register() {
        redirectL(MethodTypeInfoValue("com/corrodinggames/rts/game/i", "a", "(F)V", false,
            net.rwhps.server.dependent.redirections.game.clas.StatisticsFrameListener::class.java))
        redirectL(MethodTypeInfoValue("com/corrodinggames/rts/game/i", "a", "(ZZLcom/corrodinggames/rts/gameFramework/s;)V", false,
            net.rwhps.server.dependent.redirections.game.clas.StatisticsFrameListener::class.java))
        // The original queue drain runs after the no-map early return. Drain it
        // at the game-loop entry so lobby mutations still execute on that thread.
        @GameSimulationLayer.GameSimulationLayer_KeyWords("game running without a loaded level!!!")
        redirectL(MethodTypeInfoValue("com/corrodinggames/rts/game/i", "b", "(FI)V", true, GameLoopOperationDrain::class.java))

        // With Game Tick Injection Packet, For thread safety
        @GameSimulationLayer.GameSimulationLayer_KeyWords("Returning to battleroom in")
        redirectL(MethodTypeInfoValue("com/corrodinggames/rts/gameFramework/j/ad", "a", "(F)V", true, CompatibleThreadUnsafeArrayList::class.java))

        /*
         * BL-NULL (task-45): close the render gate `bG` at the ENTRY of `game.i.f()V`.
         *
         * f() unconditionally sets bG=false at its 5th instruction, but only AFTER calling
         * b(Z) which (under monitor `aj`) nulls `bL` and releases `aj`. Between those two
         * instructions `aj` is free while `bG` is still true, so a render thread can pass the
         * gate and then dereference a null `bL` in b(F)V -- the live NPE that killed the game
         * loop on instance b013. Closing the gate at entry removes that window without changing
         * the final state (f() sets it false regardless) and without rewriting the engine body.
         */
        redirectL(MethodTypeInfoValue("com/corrodinggames/rts/game/i", "f", "()V", true, RenderGateGuard::class.java))
    }
}
