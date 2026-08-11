plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.piremote"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.piremote"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        // Release signing key. Credentials live in ~/.gradle/gradle.properties
        // (user-level, NOT committed): piremoteReleaseStoreFile/StorePassword/
        // KeyAlias/KeyPassword. Never put the keystore or its password in git.
        // On CI / machines without the properties the signing config is simply
        // absent and release builds come out unsigned — the Android workflow
        // only builds debug APKs anyway.
        if (providers.gradleProperty("piremoteReleaseStoreFile").isPresent) {
            create("release") {
                storeFile = file(providers.gradleProperty("piremoteReleaseStoreFile").get())
                storePassword = providers.gradleProperty("piremoteReleaseStorePassword").get()
                keyAlias = providers.gradleProperty("piremoteReleaseKeyAlias").get()
                keyPassword = providers.gradleProperty("piremoteReleaseKeyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose 1.11.4 (BOM 2026.06.01). Everything else is pinned to the newest
    // version whose minCompileSdk fits compileSdk 37 + AGP 9.3.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // In-app QR scanner: zxing-android-embedded's DecoratedBarcodeView (zxing
    // core + its own Camera2 wrapper, no ML Kit → no tflite models / native libs).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Renders the settled assistant reply as Markdown (streaming stays plain text).
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3-android:0.43.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
