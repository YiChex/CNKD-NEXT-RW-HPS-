/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.manager.listener

import net.rwhps.asm.api.listener.RedirectionListener
import net.rwhps.asm.api.listener.RedirectionListenerManager
import net.rwhps.asm.data.manager.ListenerRedirectionsDataManager
import java.util.function.Supplier

class RedirectionListenerManagerImpl: RedirectionListenerManager {
    override fun invoke(desc: String, obj: Any, fallback: Supplier<RedirectionListener>, vararg args: Any?) {
        val redirection = ListenerRedirectionsDataManager.descData[desc] ?: return
        redirection.invoke(obj, desc, *args)
    }

    override fun invoke(obj: Any, desc: String, vararg args: Any?) {
        invoke(desc, obj, { RedirectionListener {_, _, _ -> } }, *args)
    }
}
