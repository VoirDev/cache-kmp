import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.publish.PublishingExtension

plugins {
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.mavenPublish) apply false
}

val projectVersion = providers.gradleProperty("releaseVersion")
    .orElse(providers.fileContents(layout.projectDirectory.file("VERSION")).asText.map { it.trim() })
    .get()

allprojects {
    group = "dev.voir"
    version = projectVersion
}

val isMavenCentralPublication = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("MavenCentral", ignoreCase = true)
}

val moduleDescriptions = mapOf(
    "cache-core" to "Typed Kotlin Multiplatform cache contracts, engine, and bounded memory tier.",
    "cache-room" to "Room-backed persistent storage for cache-kmp.",
    "cache-codec-kotlinx" to "Kotlinx.serialization JSON payload codec for cache-kmp.",
    "cache-testing" to "Deterministic test clocks and persistent-store fakes for cache-kmp.",
)

subprojects {
    pluginManager.apply("org.jetbrains.dokka")

    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = true)
            if (isMavenCentralPublication) {
                signAllPublications()
            }

            coordinates(
                groupId = rootProject.group.toString(),
                artifactId = project.name,
                version = rootProject.version.toString(),
            )

            pom {
                name.set("cache-kmp ${project.name}")
                description.set(moduleDescriptions.getValue(project.name))
                inceptionYear.set("2026")
                url.set("https://github.com/VoirDev/cache-kmp/")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }

                developers {
                    developer {
                        id.set("checksanity")
                        name.set("Gary Bezruchko")
                        email.set("hello@voir.dev")
                        organization.set("VOIR")
                        organizationUrl.set("https://voir.dev")
                    }
                }

                scm {
                    url.set("https://github.com/VoirDev/cache-kmp/")
                    connection.set("scm:git:git://github.com/VoirDev/cache-kmp.git")
                    developerConnection.set("scm:git:ssh://git@github.com/VoirDev/cache-kmp.git")
                }
            }
        }

        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "localRelease"
                    url = rootProject.layout.buildDirectory.dir("local-maven").get().asFile.toURI()
                }
            }
        }
    }
}
