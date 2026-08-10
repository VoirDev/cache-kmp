pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven {
            name = "cacheKmpLocal"
            url = uri(providers.gradleProperty("cacheRepository").get())
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "cache-kmp-consumer-samples"

include(":memory-consumer")
include(":persistent-consumer")
