pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Echo"

// Applications
include(":app-android")
include(":wearApp")

// Kotlin Multiplatform modules (shared by Android, iOS and JVM targets)
include(":common")        // the public extension API (Maven artifact)
include(":shared")        // platform abstractions, logging, errors, checksums
include(":core")          // cross-cutting policies: retry, watchdog, recovery
include(":domain")        // playback domain: queue, controller, audio fx
include(":data")          // persistence: settings, library, downloads, caches
include(":extensions")    // extension runtime: subsonic, local, validation
include(":player")        // platform engines: Media3 (Android) / AVFoundation (iOS)
include(":composeApp")    // Compose Multiplatform UI (hosted on both platforms)
