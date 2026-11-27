/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

/**
 * @date  2023/7/1 18:56
 * @author Dr (dr@der.kim)
 */
object Versions {
    // Netty Version
    const val nettyVersion = "4.1.108.Final"

    const val jLineVersion = "3.25.1"

    // Kotlin Version
    const val kotlinVersion = "2.0.0"

    // Graalvm Version
    const val graalvmVersion = "22.3.2"

    const val dokkaVersion = "1.8.10"

    /**
     * D-35: single source of truth for the third-party plugin API version.
     * Previously "1.2.0" was hardcoded in Server-All/build.gradle.kts (5 places),
     * Server-Plugin-API/build.gradle.kts and package-single-room-fixed.ps1 (15 places),
     * so a version bump had to be applied consistently by hand.
     */
    const val serverPluginApiVersion = "1.3.0"

    /** Artifact base name of the plugin API jar (without version suffix). */
    const val serverPluginApiArtifact = "server-plugin-api"
}
