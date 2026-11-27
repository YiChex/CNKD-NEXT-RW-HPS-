/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.agent

import net.rwhps.asm.data.manager.ListenerRedirectionsDataManager
import net.rwhps.asm.data.manager.RemoveRedirectionsDataManager
import net.rwhps.asm.data.manager.ReplaceRedirectionsDataManager
import net.rwhps.asm.data.manager.WrapRedirectionsDataManager
import java.security.ProtectionDomain

open class AsmCore {
    protected val setAgent = { agent: AsmAgent -> Companion.agent = agent }

    companion object {
        private var agent: AsmAgent? = null

        @JvmStatic
        fun transform(loader: ClassLoader?, className: String, classfileBuffer: ByteArray): ByteArray {
            return transform(loader, className, null, null, classfileBuffer)
        }

        @JvmStatic
        fun transform(
            loader: ClassLoader?,
            className: String,
            classBeingRedefined: Class<*>?,
            protectionDomain: ProtectionDomain?,
            classfileBuffer: ByteArray
        ): ByteArray {
            return agent?.transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer) ?: classfileBuffer
        }

        /**
         * D-01 / D-02: every class path that currently has at least one registered
         * redirection (full replace / partial replace / listener injection / method removal).
         *
         * Paths use the JVM internal form (slashes, no `.class` suffix), matching the keys of
         * `GameModularLoadClass.classPathMap`. Server-Core uses this to verify that a cached
         * game-class archive (`.bin`) actually contains the hooks the runtime table expects,
         * instead of silently running un-redirected game classes.
         */
        @JvmStatic
        fun registeredClassPaths(): Set<String> {
            val result = LinkedHashSet<String>()
            result.addAll(ReplaceRedirectionsDataManager.allClassPathCache)
            result.addAll(ReplaceRedirectionsDataManager.allRemoveSyncClassPathCache)
            result.addAll(ReplaceRedirectionsDataManager.partialClassPathCache)
            result.addAll(ListenerRedirectionsDataManager.partialClassPathCache)
            result.addAll(RemoveRedirectionsDataManager.partialClassPathCache)
            /*
             * FIX-LOOP-GUARD (P1-1, found by independent verification of 7526307): the wrap
             * registry must be listed here too. This set feeds
             * GameModularReusableLoadClass.findMissingBakedRedirections(), whose whole purpose is
             * to catch "a hook was added after the .bin cache was baked" -- the 1.1.11
             * com/corrodinggames/rts/game/i omission. Leaving wrap out meant a class registered
             * ONLY via redirectWrap would silently escape that self-check.
             *
             * Today the omission happens to be masked: java/b is also registered via
             * redirectRemove(gameLoop), so it is already in this set. The risk is future-only,
             * which is exactly why it is worth fixing now.
             */
            result.addAll(WrapRedirectionsDataManager.partialClassPathCache)
            return result
        }

        /**
         * FIX-FALSE-POSITIVE: 需要往字节码里**写入 `net/rwhps` 引用**的类路径集合。
         *
         * 与 [registeredClassPaths] 的区别：后者包含 `addAllReplace`（整类替换）目标，
         * 而整类替换把方法体换成空实现，**不产生任何 `net/rwhps` 引用**。
         *
         * 因此 `GameModularReusableLoadClass.findMissingBakedRedirections()` 不能用
         * "字节里有没有 net/rwhps" 来判断整类替换类是否烘焙过 —— 那必然误报。
         * 用本方法区分：
         *   - 在本集合中  ⇒ 注入型 hook，必须有 `net/rwhps` 字面量才算烘焙
         *   - 不在本集合中 ⇒ 整类替换型，缓存里有非空字节即算烘焙
         *
         * 实测误报（2026-10-21）：a/a/d、gameFramework/j/d、gameFramework/j/e、
         * DefaultLogSystem 四个 addAllReplace 目标被旧逻辑全部误报为 "missing baked hook"。
         */
        @JvmStatic
        fun injectionClassPaths(): Set<String> {
            val result = LinkedHashSet<String>()
            result.addAll(ReplaceRedirectionsDataManager.partialClassPathCache)
            result.addAll(ListenerRedirectionsDataManager.partialClassPathCache)
            result.addAll(RemoveRedirectionsDataManager.partialClassPathCache)
            result.addAll(WrapRedirectionsDataManager.partialClassPathCache)
            return result
        }
    }
}