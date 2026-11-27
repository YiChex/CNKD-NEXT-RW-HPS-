/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.dependent.redirections.slick

import org.newdawn.slick.Graphics
import org.newdawn.slick.Image
import org.newdawn.slick.util.Log


/**
 * 无头图形
 *
 * @author Dr (dr@der.kim)
 */
class DrGraphics(
    image: Image
): Graphics(image.texture.textureWidth, image.texture.textureHeight) {
    init {
        Log.debug("Creating Dr " + image.width + "x" + image.height)
    }

    /**
     * @see Graphics.disable
     */
    override fun disable() {
        /* ASM: ignore */
    }

    /**
     * @see Graphics.enable
     */
    override fun enable() {
        /* ASM: ignore */
    }

    /**
     * @see Graphics.destroy
     */
    override fun destroy() {
        /* ASM: ignore */
    }

    /**
     * @see Graphics.flush
     */
    override fun flush() {
        /* ASM: ignore */
    }
}