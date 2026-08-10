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
    implementation("dev.voir:cache-core:${cacheVersion.get()}")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnitPlatform()
}

val verifyNoPersistenceDependencies by tasks.registering {
    group = "verification"
    description = "Verifies the memory-only sample resolves no Room or SQLite modules."
    inputs.files(configurations.runtimeClasspath)
    doLast {
        val forbiddenFiles = inputs.files.files.filter { file ->
            file.name.contains("room", ignoreCase = true) ||
                file.name.contains("sqlite", ignoreCase = true)
        }
        check(forbiddenFiles.isEmpty()) {
            "Memory-only runtime classpath contains persistence dependencies: $forbiddenFiles"
        }
    }
}

tasks.check {
    dependsOn(verifyNoPersistenceDependencies)
}
