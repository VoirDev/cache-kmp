plugins {
    kotlin("jvm")
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
    implementation("dev.voir:cache:${cacheVersion.get()}")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnitPlatform()
}
