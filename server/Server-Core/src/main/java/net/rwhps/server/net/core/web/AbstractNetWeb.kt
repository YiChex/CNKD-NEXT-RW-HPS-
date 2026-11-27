/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.core.web

import net.rwhps.server.net.http.WebData

/**
 * @date  2023/6/27 11:53
 * @author Dr (dr@der.kim)
 */
interface AbstractNetWeb {
    fun setWebData(data: WebData)
}