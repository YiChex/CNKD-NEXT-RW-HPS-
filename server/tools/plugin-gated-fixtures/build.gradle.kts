import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.bundling.Jar
import java.util.zip.ZipFile

plugins {
    java
}

group = "net.cnkd.next.gated"
version = "1.0.0"

dependencies {
    compileOnly(files("../../Server-Plugin-API/build/libs/Server-Plugin-API.jar"))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
}

tasks.jar {
    enabled = false
}

val fixtureJar = tasks.register<Jar>("faultRecoveryFixtureJar") {
    archiveFileName.set("cnkd-gated-fault-recovery-probe-1.0.0.jar")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(sourceSets.main.get().output.classesDirs) {
        include("net/cnkd/gated/FaultRecoveryProbePlugin.class")
    }
    from("src/main/resources") {
        include("plugin.json")
    }
}

tasks.register("verifyFixture") {
    dependsOn(fixtureJar)

    doLast {
        val archive = layout.buildDirectory.file("libs/cnkd-gated-fault-recovery-probe-1.0.0.jar").get().asFile
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toList()
            check(entries.count { it == "plugin.json" } == 1) {
                "fault recovery fixture must contain exactly one root plugin.json"
            }
            check(entries.none { it.endsWith("/plugin.json") }) {
                "fault recovery fixture must not contain nested plugin.json files"
            }
            val classes = entries.filter { it.endsWith(".class") }
            check(classes == listOf("net/cnkd/gated/FaultRecoveryProbePlugin.class")) {
                "fault recovery fixture must contain only its declared implementation class; found $classes"
            }
        }
    }
}

tasks.register("buildFixture") {
    dependsOn("verifyFixture")
}
