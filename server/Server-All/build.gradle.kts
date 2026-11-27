import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.project
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipFile

dependencies {
    implementation(project(":Server-Core"))
}

val defaultCnkdLicenseMode = "allow_all"
val cnkdLicenseMode = providers.gradleProperty("cnkdLicenseMode").orElse(defaultCnkdLicenseMode)
// Keep the displayed version and the distributable version identical.
val coreVersionSource = rootProject.file("Server-Core/src/main/java/net/rwhps/server/data/global/Data.kt")
val coreReleaseVersion = Regex("SERVER_CORE_VERSION = \"CNKD NEXT ([^\"]+)\"")
    .find(coreVersionSource.readText(Charsets.UTF_8))?.groupValues?.get(1)
    ?: error("Cannot read SERVER_CORE_VERSION from ${coreVersionSource.path}")
val cnkdReleaseVersion = providers.gradleProperty("cnkdReleaseVersion").orElse(coreReleaseVersion)
require(cnkdReleaseVersion.get() == coreReleaseVersion) {
    "Release version ${cnkdReleaseVersion.get()} differs from displayed version $coreReleaseVersion"
}
// Source handoffs can supply the verified origin without shipping a .git database.
val cnkdSourceRevision = providers.gradleProperty("cnkdSourceRevision").orElse(providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map { it.trim() }).get()
val cnkdSourceDirty = providers.gradleProperty("cnkdSourceDirty").map { it.toBooleanStrict() }.orElse(providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", "status", "--porcelain", "--untracked-files=no")
}.standardOutput.asText.map { it.isNotBlank() }).get()
// D-35: single source of truth for the plugin API artifact name/version.
val pluginApiVersion = Versions.serverPluginApiVersion
val cnkdPluginApiExpectedChange = providers.gradleProperty("cnkdPluginApiExpectedChange").orElse("minor")
val generateCnkdReleaseMode = tasks.register("generateCnkdReleaseMode") {
    val outputDir = layout.buildDirectory.dir("generated/cnkdReleaseMode")
    inputs.property("cnkdLicenseMode", cnkdLicenseMode)
    outputs.dir(outputDir)

    doLast {
        val mode = cnkdLicenseMode.get().trim().ifBlank { defaultCnkdLicenseMode }
        val outputRoot = outputDir.get().asFile.apply { mkdirs() }
        File(outputRoot, "cnkd-release-mode.properties").writeText("licenseMode=$mode\n", Charsets.UTF_8)
    }
}

tasks.jar {
    dependsOn(generateCnkdReleaseMode)
    exclude("META-INF/version/**")
    exclude("**/module-info.class")
    exclude("META-INF/maven/**")
    exclude("META-INF/AL2.0")
    exclude("META-INF/LGPL2.1")
    exclude("META-INF/native-image/**")
    exclude("META-INF/proguard/**")
    exclude("META-INF/**.kotlin_module")
    exclude("META-INF/**/LICENSE.txt")
    exclude("META-INF/LICENSE")
    exclude("META-INF/NOTICE.txt")
    exclude("META-INF/NOTICE")
    exclude("META-INF/INDEX.LIST")
    exclude("META-INF/**.properties")
    exclude("META-INF/**.xml")
    exclude("META-INF/DEPENDENCIES")
    exclude("META-INF/sisu/javax.inject.Named")
    exclude("about.html")
    exclude("META-INF/LWJGL.*")
    exclude("META-INF/BC2048KE.SF")
    exclude("META-INF/BC2048KE.DSA")
    exclude("META-INF/BC1024KE.SF")
    exclude("META-INF/BC1024KE.DSA")

    manifest {
        attributes(mapOf("Main-Class" to "net.rwhps.server.Main"))
        attributes(mapOf("Launcher-Agent-Class" to "net.rwhps.server.dependent.AgentAttachData"))
        attributes(mapOf("Can-Redefine-Classes" to "true"))
        attributes(mapOf("Implementation-Title" to "CNKD NEXT"))
        attributes(mapOf("Implementation-Version" to cnkdReleaseVersion.get()))
        attributes(mapOf("CNKD-Source-Revision" to cnkdSourceRevision))
        attributes(mapOf("CNKD-Source-Dirty" to cnkdSourceDirty.toString()))
        attributes(mapOf("Implementation-Vendor" to "RW-HPS Team"))
        attributes(mapOf("Build-Jar-Time" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date().time)))
        attributes(mapOf("CNKD-License-Mode" to cnkdLicenseMode.get()))
    }

    from(layout.buildDirectory.dir("generated/cnkdReleaseMode"))

    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })
}

val releaseStamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
val releaseDirName = "CNKD-NEXT-${cnkdReleaseVersion.get()}-Public-Linux-Deploy-$releaseStamp"
val releasesRoot = providers.gradleProperty("cnkdReleasesRoot")
    .map { configured -> rootProject.file(configured) }
    .getOrElse(rootProject.file("../releases"))
val runtimeDataRoot = rootProject.file("runtime-data/data")
val runtimeDocsRoot = rootProject.file("runtime-data")
val runtimeDataDirectories = listOf("gameData", "libs", "log", "maps", "mods", "player", "plugins", "replays", "saves")
val runtimeDataFiles = listOf("ServerConfig.json", "RelayLicenseConfig.json", "RoomPermissionConfig.json", "PersistentAccessConfig.json")
val runtimeDocFiles = listOf(
    "CNKD-NEXT-AFTER-INSTALL-USER-SETUP-CN.md",
    "CNKD-NEXT-PublicList-VisualConfig.json",
    "CONFIG-HELP-CN.txt",
    "OPERATIONS-CHECKLIST-CN.txt",
    "DATA-API-GUIDE-CN.txt",
    "API-OPS-CHECK-CN.txt",
    "PLAYER-EXPERIENCE-CN.txt"
)
val optionalJavaScriptLibs = setOf(
    "graal-sdk-22.3.2.jar",
    "truffle-api-22.3.2.jar",
    "js-22.3.2.jar",
    "regex-22.3.2.jar",
    "icu4j-71.1.jar",
    "Wasm.jar"
)

fun File.hasJavaScriptPluginArchives(): Boolean {
    val pluginFiles = listFiles() ?: return false
    return pluginFiles.any { candidate ->
        if (!candidate.isFile) return@any false
        val lower = candidate.name.lowercase()
        if (!lower.endsWith(".jar") && !lower.endsWith(".zip")) return@any false
        try {
            ZipFile(candidate).use { zip ->
                val pluginJson = zip.getEntry("plugin.json") ?: return@use false
                val jsonText = zip.getInputStream(pluginJson).bufferedReader(Charsets.UTF_8).use { it.readText() }
                return@use Regex("\"main\"\\s*:\\s*\"([^\"]+\\.m?js)\"", RegexOption.IGNORE_CASE).containsMatchIn(jsonText)
            }
        } catch (_: Exception) {
            false
        }
    }
}

fun File.writeUtf8Text(text: String, bom: Boolean = false) {
    val normalized = text.replace("\r\n", "\n")
    val bytes = normalized.toByteArray(Charsets.UTF_8)
    if (bom) {
        writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes)
    } else {
        writeBytes(bytes)
    }
}

tasks.register<Sync>("prepareStartModeDeployDir") {
    dependsOn(
        tasks.jar,
        project(":Server-Plugin-API").tasks.named("jar"),
        project(":Server-Plugin-API").tasks.named("sourcesJar"),
        project(":Server-Plugin-API").tasks.named("javadocJar"),
        project(":Plugin-Tool").tasks.named("jar")
    )

    val deployDir = releasesRoot.resolve(releaseDirName)
    outputs.file(deployDir.resolve("plugin-sdk/sdk-manifest.json"))
    outputs.file(deployDir.resolve("plugin-sdk/api-compatibility.json"))
    into(deployDir)

    from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        rename { "Server-All.jar" }
    }

    from(project(":Server-Plugin-API").tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        into("plugin-sdk")
        rename { "server-plugin-api-$pluginApiVersion.jar" }
    }

    from(project(":Server-Plugin-API").tasks.named<Jar>("sourcesJar").flatMap { it.archiveFile }) {
        into("plugin-sdk")
        rename { "server-plugin-api-$pluginApiVersion-sources.jar" }
    }

    from(project(":Server-Plugin-API").tasks.named<Jar>("javadocJar").flatMap { it.archiveFile }) {
        into("plugin-sdk")
        rename { "server-plugin-api-$pluginApiVersion-javadoc.jar" }
    }

    listOf("java-minimal", "kotlin-minimal").forEach { templateName ->
        from(project(":Server-Plugin-API").tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
            into("plugin-sdk/$templateName/libs")
            rename { "server-plugin-api-$pluginApiVersion.jar" }
        }
    }

    from(project(":Plugin-Tool").tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        into("plugin-sdk/tools")
        rename { "cnkd-plugin-tool.jar" }
    }

    from(rootProject.file("docs/THIRD_PARTY_PLUGIN_INTEGRATION_CN.md")) {
        into("plugin-sdk")
    }
    from(rootProject.file("docs/plugin-sdk")) {
        into("plugin-sdk")
        includeEmptyDirs = false
        exclude("**/.gradle/**", "**/.kotlin/**", "**/build/**")
    }

    runtimeDataDirectories.forEach { directoryName ->
        from(runtimeDataRoot.resolve(directoryName)) {
            into("data/$directoryName")
            if (directoryName == "maps" || directoryName == "mods") {
                exclude("**/*")
            }
        }
    }

    runtimeDataFiles.forEach { fileName ->
        from(runtimeDataRoot.resolve(fileName)) {
            into("data")
        }
    }

    runtimeDocFiles.forEach { fileName ->
        from(runtimeDocsRoot.resolve(fileName)) {
            into(".")
        }
    }

    from(rootProject.file("docs/CNKD-NEXT-USER-GUIDE-CN.md")) {
        into(".")
    }

    doLast {
        val deployRoot = deployDir
        val dataDir = File(deployRoot, "data")
        val libsDir = File(dataDir, "libs").apply { mkdirs() }
        val pluginsDir = File(dataDir, "plugins")
        val hasJavaScriptPlugins = pluginsDir.hasJavaScriptPluginArchives()

        runtimeDataDirectories.forEach { folderName ->
            val folder = File(dataDir, folderName)
            if (folder.exists()) {
                if (folderName in listOf("cache", "log", "player", "replays", "saves")) {
                    folder.listFiles()?.forEach { child -> child.deleteRecursively() }
                }
            } else {
                folder.mkdirs()
            }
        }

        libsDir.listFiles()?.forEach { lib ->
            if (lib.length() <= 0L) {
                lib.delete()
            }
        }

        val jbsdiffTarget = File(libsDir, "jbsdiff-9810fdcbfd.jar")
        val jbsdiffSource = project(":Server-Core")
            .configurations
            .getByName("testRuntimeClasspath")
            .resolvedConfiguration
            .resolvedArtifacts
            .firstOrNull {
                it.moduleVersion.id.group == "com.github.lucasbru" &&
                    it.name == "jbsdiff" &&
                    it.extension == "jar"
            }
            ?.file
        if (jbsdiffSource != null && (!jbsdiffTarget.exists() || jbsdiffTarget.length() == 0L)) {
            jbsdiffSource.copyTo(jbsdiffTarget, overwrite = true)
        }

        if (!hasJavaScriptPlugins) {
            optionalJavaScriptLibs.forEach { libName ->
                File(libsDir, libName).takeIf { it.exists() }?.delete()
            }
        }

        runtimeDocFiles.forEach { fileName ->
            File(deployRoot, fileName).takeIf { it.exists() }?.let { doc ->
                doc.writeUtf8Text(doc.readText(Charsets.UTF_8).removePrefix("\uFEFF"), bom = true)
            }
        }

        File(deployRoot, "start.sh").writeText(
            """
            #!/usr/bin/env sh
            set -eu
            cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
            DEFAULT_JAVA_OPTS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Xms160m -Xmx1024m -Xss512k -XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=256m -XX:MaxGCPauseMillis=200 -XX:+ExitOnOutOfMemoryError"
            exec java ${'$'}{JAVA_OPTS:-${'$'}DEFAULT_JAVA_OPTS} -jar Server-All.jar
            """.trimIndent() + "\n"
        )

        File(deployRoot, "start-lowmem.sh").writeText(
            """
            #!/usr/bin/env sh
            set -eu
            cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
            DEFAULT_JAVA_OPTS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Xms96m -Xmx768m -Xss512k -XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=192m -XX:MaxGCPauseMillis=200 -XX:+ExitOnOutOfMemoryError"
            exec java ${'$'}{JAVA_OPTS:-${'$'}DEFAULT_JAVA_OPTS} -jar Server-All.jar
            """.trimIndent() + "\n"
        )

        File(deployRoot, "cleanup-pure-start.sh").writeText(
            """
            #!/usr/bin/env sh
            set -eu
            cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
            rm -f \
              data/RelayApiConfig.json \
              data/RelayConfig.json \
              data/RelayCustomRoomConfig.json \
              data/RelayEntryWelcome.txt \
              data/RelayRoomCodeConfig.json \
              data/RelayTextAuditConfig.json \
              data/Settings.bin
            echo "Pure start cleanup complete."
            """.trimIndent() + "\n"
        )

        File(deployRoot, "README-Linux.txt").writeUtf8Text(
            """
            CNKD NEXT Start Mode 免授权全功能 Linux 部署包

            首次使用:
            1. 安装 Java 17 或更高版本。
            2. 解压整个压缩包到一个全新目录，不要只替换 Server-All.jar。
            3. 打开 CONFIG-HELP-CN.txt，按说明修改 data/ServerConfig.json。
            4. 如需开放到公共列表，把 data/ServerConfig.json 中的 common.autoUpList 改为 true。
            5. 执行: chmod +x start.sh start-lowmem.sh cleanup-pure-start.sh
            6. 常规启动: ./start.sh
            7. 低内存启动: ./start-lowmem.sh
            8. 全新目录首次启动会显示 RW-HPS 许可协议；接受时请输入 Yes 并回车。

            说明:
            - data/ServerConfig.json 是主配置文件，管理端口、名称、人数、自动开局、锁图、公共列表等。
            - data/RelayLicenseConfig.json 可保持空白；当前默认构建不需要授权码。
            - data/RoomPermissionConfig.json 管理房主、管理员、普通玩家的房间内权限。
            - 默认 autoUpList=false，不会自动上传公共列表；公开服请按文档开启。
            - 默认 webPort=0，减少额外常驻开销。
            - 未放置 JavaScript 插件时，将自动跳过 Graal/Wasm 可选依赖。
            - 如果从旧 Relay 目录覆盖升级，可先执行 ./cleanup-pure-start.sh 删除不再需要的 Relay 配置文件。
            """.trimIndent() + "\n",
            bom = true
        )

        File(deployRoot, "DEPLOY-GUIDE-CN.md").writeUtf8Text(
            """
            # CNKD NEXT Start Mode 免授权全功能部署说明

            ## 版本定位
            这份部署包面向 Rusted Warfare Headless `start` 模式，适合公开服、团战服、FFA、生存图和固定地图活动服。

            它默认使用免授权全功能构建，`data/RelayLicenseConfig.json` 可以保持空白。新管理员应优先修改 `data/ServerConfig.json`，不要从旧教程里的 `Config.json` 或 `ConfigServer.json` 开始。

            ## 启动方式
            常规:
            ```sh
            ./start.sh
            ```

            低内存:
            ```sh
            ./start-lowmem.sh
            ```

            自定义:
            ```sh
            JAVA_OPTS="-Xms256m -Xmx1536m" ./start.sh
            ```

            ## 首次许可确认
            全新目录第一次启动时会显示 RW-HPS 许可协议。如果你接受协议，请在控制台输入 `Yes` 并回车。

            如果你的面板没有显示输入框，建议先在支持交互输入的终端中完成首次确认，再回到面板启动。

            ## 第一次应该修改哪些文件
            - `CONFIG-HELP-CN.txt`: 先读这份文档，它按字段解释了配置含义和常见模板。
            - `data/ServerConfig.json`: 主配置文件，管理端口、服务器名称、人数、自动开局、房间规则、锁图和公共列表字段。
            - `data/RelayLicenseConfig.json`
              - 当前默认构建不需要填写授权码。
              - 公开服只需要在 `ServerConfig.json` 中设置 `autoUpList=true`。
            - `data/RoomPermissionConfig.json`
              - 管理房主、管理员、普通玩家可以使用的命令权限。

            ## 公共列表提示
            - 默认不会自动上传公共列表，避免新管理员误公开服务器。
            - 要开放公共列表，请设置 `autoUpList=true`。
            - 如果需要稳定推广占位，可开启 `listPromotionModeEnabled`，但建议先确认普通上传能正常显示。
            - 推广模式只影响列表展示字段，不改变真实房间是否可加入。

            ## 清理旧目录
            如果你从旧的 Relay 目录覆盖升级，可执行:
            ```sh
            ./cleanup-pure-start.sh
            ```
            它会删除当前纯 start 模式不再需要的 Relay 配置文件。

            ## 可选依赖说明
            只有在 `data/plugins/` 中检测到 JavaScript 插件时，服务端才会启用:
            - `js`
            - `regex`
            - `truffle-api`
            - `graal-sdk`
            - `icu4j`
            - `Wasm.jar`

            这可以明显降低纯 start 模式下的启动内存和部署包复杂度。
            """.trimIndent() + "\n",
            bom = true
        )

        val toolJar = project(":Plugin-Tool").tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val javaExecutable = File(
            System.getProperty("java.home"),
            if (System.getProperty("os.name").lowercase().contains("win")) "bin/java.exe" else "bin/java"
        )
        val sdkDirectory = File(deployRoot, "plugin-sdk")
        val apiCompatibility = File(sdkDirectory, "api-compatibility.json")
        val abiProcess = ProcessBuilder(
            javaExecutable.absolutePath,
            "-jar",
            toolJar.absolutePath,
            "api-compare",
            File(sdkDirectory, "api-baselines/server-plugin-api-1.1.0.json").absolutePath,
            File(sdkDirectory, "server-plugin-api-$pluginApiVersion.jar").absolutePath,
            "--expected-change",
            cnkdPluginApiExpectedChange.get(),
            "--json",
            apiCompatibility.absolutePath,
            "--force"
        ).redirectErrorStream(true).inheritIO().start()
        check(abiProcess.waitFor() == 0) { "Plugin API ABI compatibility gate failed" }

        val sdkManifest = File(sdkDirectory, "sdk-manifest.json")
        val manifestProcess = ProcessBuilder(
            javaExecutable.absolutePath,
            "-jar",
            toolJar.absolutePath,
            "sdk-manifest",
            sdkDirectory.absolutePath,
            "--json",
            sdkManifest.absolutePath,
            "--force"
        ).redirectErrorStream(true).inheritIO().start()
        check(manifestProcess.waitFor() == 0) { "Plugin SDK manifest generation failed" }
    }
}

tasks.register<Zip>("packageStartModeDeployZip") {
    dependsOn("prepareStartModeDeployDir")
    destinationDirectory.set(releasesRoot)
    archiveFileName.set("$releaseDirName.zip")
    from(releasesRoot.resolve(releaseDirName))
}
