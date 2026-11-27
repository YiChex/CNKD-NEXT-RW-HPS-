/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.dependent.redirections.slick

import net.rwhps.asm.data.MethodTypeInfoValue
import net.rwhps.asm.redirections.replace.def.BasicDataRedirections
import net.rwhps.server.dependent.redirections.MainRedirections
import net.rwhps.server.util.annotations.NeedHelp
import net.rwhps.server.util.annotations.mark.AsmMark
import net.rwhps.server.util.classload.GameModularLoadClass
import net.rwhps.server.util.inline.accessibleConstructor
import net.rwhps.server.util.inline.findMethod
import net.rwhps.server.util.inline.readAsClassBytes
import net.rwhps.server.util.inline.toClass

//关闭傻逼格式化
//@formatter:off

/**
 * @author Dr (dr@der.kim)
 */
@NeedHelp
@AsmMark.ClassLoaderCompatible
class SlickRedirections: MainRedirections {
    override fun register() {
        addAllReplace("org/newdawn/slick/opengl/renderer/VBORenderer")

        redirectR(MethodTypeInfoValue("org/newdawn/slick/opengl/TextureImpl", "getTextureHeight", "()I"), BasicDataRedirections.INT)
        redirectR(MethodTypeInfoValue("org/newdawn/slick/opengl/TextureImpl", "getTextureWidth", "()I"), BasicDataRedirections.INT)

        redirectR(MethodTypeInfoValue("org/newdawn/slick/Input","poll", "(II)V"), BasicDataRedirections.NULL)
        redirectR(MethodTypeInfoValue("org/newdawn/slick/Music","poll", "(I)V"), BasicDataRedirections.NULL)

        // Remove game mouse cursor
        redirectR(MethodTypeInfoValue("org/newdawn/slick/AppGameContainer", "setMouseCursor", "(Lorg/newdawn/slick/Image;II)V"), BasicDataRedirections.NULL)
        redirectR(MethodTypeInfoValue("org/newdawn/slick/AppletGameContainer", "setMouseCursor", "(Lorg/newdawn/slick/Image;II)V"), BasicDataRedirections.NULL)

        // Replace version number
        redirectR(MethodTypeInfoValue("org/newdawn/slick/GameContainer", "getBuildVersion", "()I")) { obj: Any, _: String, _: Class<*>, _: Array<out Any?> ->
            val classIn = obj as Class<*>
            SilckClassPathProperties.SlickLog.toClass(classIn.classLoader)!!.findMethod("info", String::class.java)!!
                .invoke(null, "Slick Build : RW-HPS-Headless-Slick RwGame#84")
            0
        }

        // Disable sound loader
        addAllReplace("org/newdawn/slick/Sound")
        redirectR(MethodTypeInfoValue("org/newdawn/slick/Sound", "playing", "()Z"), BasicDataRedirections.BOOLEANT)

        // Replace default graphics processor
        redirectR(MethodTypeInfoValue("org/newdawn/slick/opengl/pbuffer/GraphicsFactory", "createGraphics", "(Lorg/newdawn/slick/Image;)Lorg/newdawn/slick/Graphics;")) { obj: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            val classIn = obj as Class<*>
            val classLoader = classIn.classLoader
            return@redirectR SilckClassPathProperties.Graphics.toClass(classLoader)!!.cast(
                    if (classLoader is GameModularLoadClass) {
                        classLoader.loadClassBytes(
                                SilckClassPathProperties.DrGraphics, SilckClassPathProperties.DrGraphics.readAsClassBytes()
                        )!!
                    } else {
                        SilckClassPathProperties.DrGraphics.toClass(classLoader)!!
                    }.accessibleConstructor(SilckClassPathProperties.Image.toClass(classLoader)!!).newInstance(args[0])
            )
        }
    }
}