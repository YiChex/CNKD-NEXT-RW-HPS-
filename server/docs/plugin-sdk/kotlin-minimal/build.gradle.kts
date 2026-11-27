import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.0"
}

group = "example.cnkd"
version = "1.0.0"

val pluginApiJar = file(
    providers.gradleProperty("cnkdPluginApiJar")
        .orElse("libs/server-plugin-api-1.3.0.jar")
        .get()
)

if (!pluginApiJar.isFile) {
    throw GradleException(
        "CNKD Plugin API JAR is missing. Copy it to libs/server-plugin-api-1.3.0.jar " +
            "or pass -PcnkdPluginApiJar=<absolute path to Server-Plugin-API.jar>."
    )
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(files(pluginApiJar))
}

kotlin {
    jvmToolchain(17)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
}
