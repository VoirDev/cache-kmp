plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

val cacheVersion = providers.gradleProperty("cacheVersion")
    .orElse(
        providers.fileContents(rootProject.layout.projectDirectory.file("../../VERSION"))
            .asText
            .map { it.trim() },
    )

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("dev.voir:cache-core:${cacheVersion.get()}")
    implementation("dev.voir:cache-room:${cacheVersion.get()}")
    implementation("dev.voir:cache-codec-kotlinx:${cacheVersion.get()}")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnitPlatform()
}
