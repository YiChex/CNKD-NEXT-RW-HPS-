/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.classload

import net.rwhps.server.struct.map.OrderedMap
import net.rwhps.server.util.file.FileUtils
import java.net.URLClassLoader

/**
 * 插件类加载器, 隔离插件
 *
 * ## 加载
 * 每个插件单独使用此加载器, 每个加载器示例代表一个插件
 *
 * ## 链接
 * 当此插件需要使用其他插件时(前置), 那么需要手动声明对应插件
 * 才可以在本加载器中找到对应Class
 *
 * @date 2024/5/11 下午12:31
 * @author Dr (dr@der.kim)
 */
class PluginModularLoadClass(
    private val mainClassLoader: ClassLoader,
    private val jdkClassLoader: ClassLoader,
    private val pluginClassLoaders: OrderedMap<String, PluginModularLoadClass>
): ClassLoader() {
    private lateinit var pluginClassLoader: ClassLoader

    fun loadPlugin(fileUtils: FileUtils): ClassLoader {
        pluginClassLoader = URLClassLoader(arrayOf(fileUtils.file.toURI().toURL()), this)
        return pluginClassLoader
    }
}