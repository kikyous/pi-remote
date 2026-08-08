plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    // Kotlin 2.0+ ships the Compose compiler as a plugin matching the Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
