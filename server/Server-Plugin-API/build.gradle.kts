description = "Stable Java API for CNKD NEXT third-party plugins"

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
}

tasks.jar {
    manifest {
        attributes("Implementation-Title" to "CNKD NEXT Plugin API")
        attributes("Implementation-Version" to Versions.serverPluginApiVersion)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "net.cnkd.next"
            artifactId = "server-plugin-api"
            version = Versions.serverPluginApiVersion
            from(components["java"])
            pom {
                name.set("CNKD NEXT Plugin API")
                description.set("Stable Java-first API for trusted CNKD NEXT single-room server plugins")
                url.set("https://github.com/YiChex/CNKD-NEXT-RW-HPS")
                licenses {
                    license {
                        name.set("GNU Affero General Public License v3.0")
                        url.set("https://www.gnu.org/licenses/agpl-3.0.html")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("cnkd-next")
                        name.set("CNKD NEXT Contributors")
                    }
                }
                scm {
                    url.set("https://github.com/YiChex/CNKD-NEXT-RW-HPS")
                    connection.set("scm:git:https://github.com/YiChex/CNKD-NEXT-RW-HPS.git")
                    developerConnection.set("scm:git:ssh://git@github.com/YiChex/CNKD-NEXT-RW-HPS.git")
                }
            }
        }
    }
}
