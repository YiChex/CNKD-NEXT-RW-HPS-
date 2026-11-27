import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.bundling.Jar
import java.util.zip.ZipFile

plugins {
    java
}

group = "net.cnkd.next.gatec"
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

val fixtures = linkedMapOf(
    "console" to Fixture("cnkd-gatec-console-probe-1.0.0.jar", "ConsoleProbePlugin"),
    "player-command" to Fixture("cnkd-gatec-player-command-probe-1.0.0.jar", "PlayerCommandProbePlugin"),
    "player-events" to Fixture("cnkd-gatec-player-events-probe-1.0.0.jar", "PlayerEventsProbePlugin"),
    "game-events" to Fixture("cnkd-gatec-game-events-probe-1.0.0.jar", "GameEventsProbePlugin"),
    "scheduler" to Fixture("cnkd-gatec-scheduler-probe-1.0.0.jar", "SchedulerProbePlugin")
)

data class Fixture(
    val archiveName: String,
    val implementationClass: String
)

val fixtureTasks = fixtures.map { (fixtureId, fixture) ->
    tasks.register<Jar>("${fixtureId.replace("-", "")}FixtureJar") {
        archiveFileName.set(fixture.archiveName)
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(sourceSets.main.get().output.classesDirs) {
            include("net/cnkd/gatec/${fixture.implementationClass}.class")
        }
        from("src/main/resources/descriptors") {
            include("$fixtureId.json")
            rename { "plugin.json" }
        }
    }
}

tasks.register("verifyFixtures") {
    dependsOn(fixtureTasks)

    doLast {
        fixtures.forEach { (fixtureId, fixture) ->
            val archive = layout.buildDirectory.file("libs/${fixture.archiveName}").get().asFile
            val expectedClass = "net/cnkd/gatec/${fixture.implementationClass}.class"
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.toList()
                check(entries.count { it == "plugin.json" } == 1) {
                    "$fixtureId must contain exactly one root plugin.json"
                }
                check(entries.count { it.endsWith("/plugin.json") } == 0) {
                    "$fixtureId must not contain nested plugin.json files"
                }
                val classes = entries.filter { it.endsWith(".class") }
                check(classes == listOf(expectedClass)) {
                    "$fixtureId must contain only $expectedClass; found $classes"
                }
            }
        }
    }
}

tasks.register("buildFixtures") {
    dependsOn("verifyFixtures")
}
