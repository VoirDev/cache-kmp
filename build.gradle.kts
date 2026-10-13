import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.publish.PublishingExtension

plugins {
    base
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

val libraryDescription =
    "One Kotlin Multiplatform cache for memory, persistent values, and streamed files."

subprojects {
    pluginManager.apply("org.jetbrains.dokka")

    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            if (isMavenCentralPublication) {
                signAllPublications()
            }

            coordinates(
                groupId = rootProject.group.toString(),
                artifactId = "cache",
                version = rootProject.version.toString(),
            )

            pom {
                name.set("cache-kmp")
                description.set(libraryDescription)
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
