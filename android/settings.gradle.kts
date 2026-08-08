pluginManagement {
    repositories {
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
        google()
        // Direct Maven Central is IP-blocked on this machine; Aliyun mirrors it.
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
        // MarkdownText needs JitPack-hosted artifacts.
        maven("https://jitpack.io")
    }
}

rootProject.name = "PiRemote"
include(":app")
