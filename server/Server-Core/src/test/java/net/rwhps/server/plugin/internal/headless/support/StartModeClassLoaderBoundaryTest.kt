/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

internal class StartModeClassLoaderBoundaryTest {
    @Test
    fun mainLoaderClassesDoNotLinkTheInjectedGameFacade() {
        val forbiddenReferences = listOf(
            "net/rwhps/server/plugin/internal/headless/inject/core/GameEngine",
            "net/rwhps/server/plugin/internal/headless/inject/core/link/LinkGameFunction"
        )
        val classesRoot = File(
            StartModeReadinessSupport::class.java.protectionDomain.codeSource.location.toURI()
        ).toPath()

        Files.walk(classesRoot).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".class") }
                .filter { path -> isMainLoaderClass(classesRoot.relativize(path).toString().replace(File.separatorChar, '/')) }
                .forEach { path ->
                    val constantPoolText = Files.readAllBytes(path).toString(Charsets.ISO_8859_1)
                    val relativePath = classesRoot.relativize(path).toString().replace(File.separatorChar, '/')
            forbiddenReferences.forEach { forbiddenReference ->
                assertFalse(
                    constantPoolText.contains(forbiddenReference),
                            "$relativePath must use AbstractLinkGameFunction instead of $forbiddenReference"
                )
            }
                }
        }
    }

    private fun isMainLoaderClass(relativePath: String): Boolean {
        return !relativePath.startsWith("net/rwhps/server/plugin/internal/headless/inject/") &&
            !relativePath.startsWith("com/corrodinggames/rts/") &&
            !relativePath.startsWith("net/rwhps/server/dependent/redirections/game/clas/")
    }
}
