/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

import org.gradle.api.Project
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.repositories
import java.io.File
import java.io.IOException
import java.nio.file.Files

fun Project.setRepositories() {
    this.repositories {
        maven(url = "https://jitpack.io") {
            content {
                includeModule("com.github.lucasbru", "jbsdiff")
            }
            metadataSources {
                mavenPom()
                artifact()
            }
        }
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
        maven(url = "https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        maven(url = "https://repo.huaweicloud.com/repository/maven")
        mavenCentral()
        maven(url = "https://plugins.gradle.org/m2")
        gradlePluginPortal()
    }
}

fun Project.makeDependTree() {
    try {
        val project = this

        val implementationFile = File("${project.rootDir}/${this.project.name}/${Data.mavenPath}/${this.project.name}/implementation.txt")
        val compileOnlyFile = File("${project.rootDir}/${this.project.name}/${Data.mavenPath}/${this.project.name}/compileOnly.txt")

        implementationFile.fuckGithub()
        compileOnlyFile.fuckGithub()

        var implementation = ""
        var compileOnly = ""
        val onlyList = ArrayList<String>()

        project.configurations.findByName("compileOnly")!!.allDependencies.forEach {
            val result = configurations.detachedConfiguration(it).resolvedConfiguration
            result.firstLevelModuleDependencies.forEach { moduleDependency ->
                moduleDependency.allModuleArtifacts.forEach { dependency ->
                    onlyList.add(dependency.moduleVersion.id.group + dependency.moduleVersion.id.name + dependency.classifier)
                }
            }
        }

        project.configurations.findByName("compileClasspath")!!.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
            if (onlyList.contains(artifact.moduleVersion.id.group + artifact.moduleVersion.id.name + artifact.classifier)) {
                compileOnly += "${artifact.type}:${artifact.moduleVersion.id.group}:${artifact.moduleVersion.id.name}:${artifact.moduleVersion.id.version}:${artifact.classifier}${Data.lineSeparator}"
            } else {
                implementation += "${artifact.type}:${artifact.moduleVersion.id.group}:${artifact.moduleVersion.id.name}:${artifact.moduleVersion.id.version}:${artifact.classifier}${Data.lineSeparator}"
            }
        }
        implementationFile.writeTextIfChanged(implementation)
        compileOnlyFile.writeTextIfChanged(compileOnly)
    } catch (_: Exception) {
        // 忽略
    }
}

/**
 * D-34: [makeDependTree] runs on every `jar` task and used to rewrite the
 * `src/main/resources/maven` manifest files unconditionally. Those files are tracked in
 * git, so any rebuild dirtied the working tree (and changed every file hash) even when the
 * resolved dependency set was identical. Only touch the file when its content changes.
 */
fun File.writeTextIfChanged(content: String) {
    if (this.exists() && this.isFile && runCatching { this.readText() }.getOrNull() == content) {
        return
    }
    this.fuckGithub()
    this.writeText(content)
}

fun File.fuckGithub() {
    try {
        if (this.parentFile == null || this.parentFile!!.delete()) {
            return
        }
        this.parentFile?.mkdirs()

        if (this.isDirectory) {
            return
        }

        if (!this.exists()) {
            try {
                Files.createFile(toPath())
            } catch (e: IOException) {
                println(path)
                println(canWrite())
                error(e)
            }
        }
    } catch (_: Exception) {
        println("remove Files Error???")
    }
}
