pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        maven("https://repo.huaweicloud.com/repository/maven")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "CNKD-Start-Mode-Standalone"

include(":TimeTaskQuartz")
include(":ASM-Framework")
include(":Server-Plugin-API")
include(":Plugin-Tool")
include(":Server-Core")
include(":Server-All")
