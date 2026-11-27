/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

dependencies {
    compileOnlyAndTest("org.ow2.asm:asm:9.7")
    compileOnlyAndTest("org.ow2.asm:asm-tree:9.7")
}

tasks.jar {
    project.makeDependTree()
}

fun DependencyHandler.compileOnlyAndTest(dependencyNotation: Any) {
    this.testImplementation(dependencyNotation)
    this.compileOnly(dependencyNotation)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.github.RW-HPS"
            artifactId = "ASM-Framework"
            description = "Dedicated to Rusted Warfare(RustedWarfare) High Performance Server"

            from(components.getByName("java"))

            pom {
                scm {
                    url.set("https://github.com/deng-rui/RW-HPS")
                    connection.set("scm:https://github.com/deng-rui/RW-HPS.git")
                    developerConnection.set("scm:git@github.com:deng-rui/RW-HPS.git")
                }

                licenses {
                    license {
                        name.set("GNU AGPLv3")
                        url.set("https://github.com/deng-rui/RW-HPS/blob/master/LICENSE")
                    }
                }

                developers {
                    developer {
                        id.set("RW-HPS")
                        name.set("RW-HPS Technologies")
                    }
                }

            }

            pom.withXml {
                val root = asNode()
                root.appendNode("description", project.description)
                root.appendNode("name", project.name)
                root.appendNode("url", "https://github.com/deng-rui/RW-HPS")
            }
        }
    }
}