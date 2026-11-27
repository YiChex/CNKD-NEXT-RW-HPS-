/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

import org.jetbrains.dokka.base.DokkaBase
import org.jetbrains.dokka.base.DokkaBaseConfiguration
import org.jetbrains.dokka.gradle.AbstractDokkaTask
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.*

buildscript {
    setRepositories()

    dependencies {
        classpath("org.jetbrains.dokka:dokka-base:${Versions.dokkaVersion}")
    }
}

setRepositories()

plugins {
    id("org.jetbrains.kotlin.jvm") version Versions.kotlinVersion
    id("org.jetbrains.dokka") version Versions.dokkaVersion
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "maven-publish")

    setRepositories()

    tasks.jar {
        manifest {
            attributes(mapOf("Implementation-Vendor" to "RW-HPS Team"))
            attributes(mapOf("Build-Jar-Time" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date().time)))
        }
    }


    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    tasks.withType<JavaCompile> {
        /* 使用Java17做标准语法并编译 */
        sourceCompatibility = JvmTarget.JVM_11.target
        targetCompatibility = JvmTarget.JVM_11.target

        options.encoding = "UTF-8"
    }
}

subprojects {
    afterEvaluate {
        if (
            project.path == ":ASM-Framework" ||
            project.path == ":TimeTaskQuartz" ||
            project.path == ":Server-Plugin-API" ||
            project.path == ":Server-Core"
            ) {
            configureDokka()
        }
    }
}
rootProject.configureDokka()

fun Project.configureDokka() {
    try {
        val isRoot = (this@configureDokka == rootProject)
        if (!isRoot) {
            this@configureDokka.apply(plugin = "org.jetbrains.dokka")
        }

        dependencies {
            dokkaPlugin("org.jetbrains.dokka:kotlin-as-java-plugin:${Versions.dokkaVersion}")
            dokkaPlugin("org.jetbrains.dokka:android-documentation-plugin:${Versions.dokkaVersion}")
        }

        tasks.withType<AbstractDokkaTask>().configureEach {
            pluginConfiguration<DokkaBase, DokkaBaseConfiguration> {
                this.footerMessage = """Copyright 2020-${
                    LocalDateTime.now().year
                } <a href="https://github.com/RW-HPS">RW-HPS Technologies</a> and contributors.
            Source code:
            <a href="https://github.com/deng-rui/RW-HPS">GitHub</a>
            """.trimIndent()
            }
        }

        tasks.withType<DokkaTask>().configureEach {
            dokkaSourceSets.configureEach {
                perPackageOption {
                    skipDeprecated.set(true)
                }
            }
        }

        if (isRoot) {
            tasks.named<AbstractDokkaTask>("dokkaHtmlMultiModule").configure {
                outputDirectory.set(
                        rootProject.projectDir.resolve("Java-Doc/pages/snapshot")
                )
            }
        }
    } catch (_: Exception) {
        println("导入项目时可忽略错误, 如果在编译时发生, 请检查")
    }
}
