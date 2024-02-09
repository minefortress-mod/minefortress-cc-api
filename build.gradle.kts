import net.fabricmc.loom.task.RemapJarTask

import java.net.URI

plugins {
    id("fabric-loom") version "1.3-SNAPSHOT"
    id("io.github.juuxel.loom-quiltflower") version "1.6.0"
    id("org.cadixdev.licenser") version "0.6.1"
    id("maven-publish")
}

val fabricApiVersion: String = providers.gradleProperty("fabric_api_version").get()

allprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "org.cadixdev.licenser")
    apply(plugin = "fabric-loom")

    group = "dev.onyxstudios.cardinal-components-api"

    version = System.getenv("TAG_NAME") ?: providers.gradleProperty("mod_version").get()


    repositories {
        maven {
            name = "Ladysnake Mods"
            url = URI("https://maven.ladysnake.org/releases")
            content {
                includeGroup("io.github.ladysnake")
                includeGroup("org.ladysnake")
                includeGroupByRegex("dev\\.emi.*")
                includeGroupByRegex("dev\\.onyxstudios.*")
            }
        }
        maven {
            name = "JitPack"
            url = URI("https://jitpack.io")
        }
    }

    dependencies {
        val props = project.properties
        minecraft("com.mojang:minecraft:${props["minecraft_version"]}")
        mappings("net.fabricmc:yarn:${props["minecraft_version"]}+build.${props["yarn_mappings"]}:v2")
        modApi("net.fabricmc:fabric-loader:${props["loader_version"]}")
        modApi(fabricApi.module("fabric-api-base", fabricApiVersion))
        modImplementation(fabricApi.module("fabric-networking-api-v1", fabricApiVersion))
        modImplementation(fabricApi.module("fabric-lifecycle-events-v1", fabricApiVersion))

        modCompileOnly(fabricApi.module("fabric-gametest-api-v1", fabricApiVersion))
        modImplementation("org.ladysnake:elmendorf:${props["elmendorf_version"]}")

        compileOnly("com.google.code.findbugs:jsr305:3.0.2")
        compileOnly("com.demonwav.mcdev:annotations:1.0")
        compileOnly("org.jetbrains:annotations:24.0.1")
    }

    repositories {
        mavenLocal()
    }

    tasks.processResources {
        inputs.property("version", project.version)

        filesMatching("fabric.mod.json") {
            expand(mapOf("version" to project.version))
        }
    }

    java {
        // Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
        // if it is present.
        // If you remove this line, sources will not be generated.
        withSourcesJar()
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    tasks.withType<GenerateModuleMetadata>().configureEach {
        enabled = false
    }

    tasks.jar {
        from(rootProject.file("LICENSE.md")) {
            rename { "LICENSE_${project.name.replace("-", "_")}"}
        }
    }
}

tasks.javadoc {
    with (options as StandardJavadocDocletOptions) {
        source = "16"
        encoding = "UTF-8"
        charset("UTF-8")
        memberLevel = JavadocMemberLevel.PACKAGE
        links(
            "https://guava.dev/releases/21.0/api/docs/",
            "https://asm.ow2.io/javadoc/",
            "https://docs.oracle.com/javase/8/docs/api/",
            "http://jenkins.liteloader.com/job/Mixin/javadoc/",
            "https://logging.apache.org/log4j/2.x/log4j-api/apidocs/"
            // Need to add minecraft jd publication etc once there is one available
        )
        // Disable the crazy super-strict doclint tool in Java 8
        addStringOption("Xdoclint:none", "-quiet")
    }

    allprojects.forEach { p ->
        source(p.sourceSets.main.map { it.allJava.srcDirs })
    }

    classpath = project.files(sourceSets.main.map { it.compileClasspath })
    include("**/api/**")
    isFailOnError = false
}

val javadocJar by tasks.registering(Jar::class) {
    dependsOn(tasks.javadoc)
    from(tasks.javadoc.map { it.destinationDir!! })
    //Set as `fatjavadoc` to prevent an ide form trying to use this javadoc, over using the modules javadoc
    archiveClassifier.set("fatjavadoc")
}

tasks.assemble.configure {
    dependsOn(javadocJar)
}

subprojects {
    version = rootProject.version

    sourceSets.create("testmod") {
        compileClasspath += sourceSets.main.get().compileClasspath
        runtimeClasspath += sourceSets.main.get().runtimeClasspath
    }

    dependencies {
        "testmodImplementation"(sourceSets.main.map { it.output })
    }

    extensions.configure(PublishingExtension::class.java) {
        publications {
            create("relocation", MavenPublication::class.java) {
                pom {
                    // Old artifact coordinates
                    groupId = "io.github.onyxstudios.Cardinal-Components-API"

                    distributionManagement {
                        relocation {
                            // New artifact coordinates
                            groupId = "dev.onyxstudios.cardinal-components-api"
                            message = "groupId has been changed"
                        }
                    }
                }
            }
            create("mavenJava", MavenPublication::class.java) {
                from(components.getByName("java"))
            }
        }
    }

    tasks.javadoc.configure {
        isEnabled = false
    }
}

val remapMavenJar by tasks.registering(RemapJarTask::class) {
    inputFile.set(tasks.jar.flatMap { it.archiveFile })
    archiveFileName.set("${project.properties["archivesBaseName"]}-${project.version}-maven.jar")
    addNestedDependencies = false
    dependsOn(tasks.jar)
}
tasks.assemble.configure {
    dependsOn(remapMavenJar)
}

extensions.configure(PublishingExtension::class.java) {
    publications {
        val mavenJava by creating(MavenPublication::class) {
            artifact(remapMavenJar) {
                builtBy(remapMavenJar)
            }

            artifact(tasks.named("sourcesJar")) {
                builtBy(tasks.remapSourcesJar)
            }

            artifact(tasks.named("javadocJar"))

            pom.withXml {
                val depsNode = asNode().appendNode("dependencies")
                subprojects.forEach {
                    val depNode = depsNode.appendNode("dependency")
                    depNode.appendNode("groupId", it.group)
                    depNode.appendNode("artifactId", it.name)
                    depNode.appendNode("version", it.version)
                    depNode.appendNode("scope", "compile")
                }
            }
        }
        // Required until the deprecation is removed. CCA's main jar that is published to maven does not contain sub modules.
        @Suppress("UnstableApiUsage")
        loom.disableDeprecatedPomGeneration(mavenJava)
    }
}

subprojects.forEach { tasks.remapJar.configure { dependsOn("${it.path}:remapJar") } }

dependencies {
    // used by the test mod
    modImplementation(fabricApi.module("fabric-api-base", fabricApiVersion))
    modImplementation(fabricApi.module("fabric-object-builder-api-v1", fabricApiVersion))
    modImplementation(fabricApi.module("fabric-rendering-v1", fabricApiVersion))
    modImplementation(fabricApi.module("fabric-lifecycle-events-v1", fabricApiVersion))
    modImplementation(fabricApi.module("fabric-item-api-v1", fabricApiVersion))
    modImplementation(fabricApi.module("fabric-item-group-api-v1", fabricApiVersion))
    modImplementation(fabricApi.module("fabric-events-interaction-v0", fabricApiVersion))
    modImplementation(fabricApi.module("fabric-api-lookup-api-v1", fabricApiVersion))
    modImplementation(fabricApi.module("fabric-command-api-v2", fabricApiVersion))
    modImplementation(fabricApi.module("fabric-gametest-api-v1", fabricApiVersion))
    modRuntimeOnly(fabricApi.module("fabric-networking-api-v1", fabricApiVersion))
    modRuntimeOnly(fabricApi.module("fabric-resource-loader-v0", fabricApiVersion))
    modRuntimeOnly(fabricApi.module("fabric-events-interaction-v0", fabricApiVersion))
    modRuntimeOnly(fabricApi.module("fabric-registry-sync-v0", fabricApiVersion))

    testCompileOnly("com.google.code.findbugs:jsr305:3.0.2")

    afterEvaluate {
        subprojects.forEach {
            api(project(path = ":${it.name}", configuration = "namedElements"))
            include(project("${it.name}:"))
        }
    }
}

publishing {
    repositories {
        mavenLocal()
//        maven {
//            name = "GitHubPackages"
//            url = uri("https://maven.pkg.github.com/" + System.getenv("GITHUB_REPOSITORY"))
//            credentials {
//                username = System.getenv("GITHUB_USERNAME")
//                password = System.getenv("GITHUB_TOKEN")
//            }
//        }
    }
}

subprojects.forEach {sub ->
    publishing {
        publications {
            create("mavenJava-${sub.name}", MavenPublication::class) {
                from(sub.components["java"])
                groupId = project.group as String
                artifactId = sub.name
                version = project.version as String
            }
        }
    }
}
