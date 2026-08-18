pluginManagement {
    repositories {
        // dl.google.com (the default google() endpoint) is unreachable from
        // this network; Aliyun mirrors Google Maven and Maven Central.
        maven("https://maven.aliyun.com/repository/google")
        google()
        // Direct Maven Central is IP-blocked on this machine; Aliyun mirrors it.
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        google()
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
        // MarkdownText needs JitPack-hosted artifacts.
        maven("https://jitpack.io")
    }
}

rootProject.name = "PiRemote"
include(":composeApp")

plugins {
    id("com.android.application") version "9.3.1" apply false
    // Kotlin Multiplatform: one module, android + ios targets.
    id("org.jetbrains.kotlin.multiplatform") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    // Kotlin 2.0+ ships the Compose compiler as a plugin matching the Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    // Compose Multiplatform: runtime/foundation/material3/ui for android + ios.
    id("org.jetbrains.compose") version "1.11.1" apply false
}
