plugins {
    java
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
    // After publishing the local API, add its Maven repository here.
}

dependencies {
    // Recommended after API publication:
    // compileOnly("net.cnkd.next:server-plugin-api:1.3.0")

    compileOnly(files(pluginApiJar))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
}
