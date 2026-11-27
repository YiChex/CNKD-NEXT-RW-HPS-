plugins {
    application
}

description = "Cross-platform CNKD NEXT plugin validation and migration CLI"

configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    implementation(project(":Server-Plugin-API"))
    implementation("com.google.code.gson:gson:2.10")
    implementation("org.ow2.asm:asm:9.7")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
}

application {
    mainClass.set("net.rwhps.server.plugin.tool.PluginToolMain")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    archiveFileName.set("cnkd-plugin-tool.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to application.mainClass.get())
        attributes("Implementation-Title" to "CNKD NEXT Plugin Tool")
        attributes("Implementation-Version" to "1.0.0")
    }
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class")
    from(configurations.runtimeClasspath.get().map { dependency ->
        if (dependency.isDirectory) dependency else zipTree(dependency)
    })
}
