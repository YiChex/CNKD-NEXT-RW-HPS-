/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.classload

import net.rwhps.server.util.file.FileUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class GameModularReusableLoadClassTest {
    @Test
    fun bundledGameClassCacheLoadsLibRocketRootInTheGameClassLoader() {
        val cacheFile = findRuntimeGameClassCache()
        assertTrue(cacheFile.isFile, "runtime GameModularReusableClass.bin is required for headless startup")

        val parent = javaClass.classLoader.parent ?: ClassLoader.getPlatformClassLoader()
        val loader = GameModularReusableLoadClass(javaClass.classLoader, parent)
        loader.readData(FileUtils(cacheFile))

        val rootClass = loader.loadClass("com.corrodinggames.librocket.scripts.Root")
        assertNotNull(rootClass)
        assertEquals(loader, rootClass.classLoader)
    }

    @Test
    fun injectedGameEngineInitializesInsideTheGameClassLoader() {
        val cacheFile = findRuntimeGameClassCache()
        assertTrue(cacheFile.isFile, "runtime GameModularReusableClass.bin is required for headless startup")

        val parent = javaClass.classLoader.parent ?: ClassLoader.getPlatformClassLoader()
        val loader = GameModularReusableLoadClass(javaClass.classLoader, parent)
        loader.readData(FileUtils(cacheFile))
        addInjectedGameEngineClasses(loader)

        val gameEngineClass = Class.forName(
            "net.rwhps.server.plugin.internal.headless.inject.core.GameEngine",
            true,
            loader
        )
        assertEquals(loader, gameEngineClass.classLoader)
    }

    private fun findRuntimeGameClassCache(): File {
        val relativePath = "runtime-data/data/gameData/GameModularReusableClass.bin"
        return generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?: File(relativePath)
    }

    private fun addInjectedGameEngineClasses(loader: GameModularReusableLoadClass) {
        val classesRoot = File(
            GameModularReusableLoadClass::class.java.protectionDomain.codeSource.location.toURI()
        )
        val packageRoot = File(
            classesRoot,
            "net/rwhps/server/plugin/internal/headless/inject/core"
        )
        require(packageRoot.isDirectory) { "injected GameEngine classes missing: ${packageRoot.absolutePath}" }

        Files.list(packageRoot.toPath()).use { paths ->
            paths
                .filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith("GameEngine") && name.endsWith(".class")
                }
                .forEach { path ->
                    val name = path.fileName.toString().removeSuffix(".class")
                    loader.addClassBytes(
                        "net.rwhps.server.plugin.internal.headless.inject.core.$name",
                        Files.readAllBytes(path),
                        cove = true
                    )
                }
        }
    }
}
