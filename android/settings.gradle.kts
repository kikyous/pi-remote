pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MarkdownText (com.github.jeziellago:compose-markdown) is JitPack-only.
        maven("https://jitpack.io")
    }
}

rootProject.name = "PiRemote"
include(":app")
