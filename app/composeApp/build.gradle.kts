import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// CMP 1.11.1 component versions (from ComposeBuildConfig): runtime/foundation/ui
// track the plugin at 1.11.1, material3 is decoupled at 1.9.0, icons are
// frozen upstream at 1.7.3.
val cmpVersion = "1.11.1"

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iOS targets — Route B: real device + Apple-Silicon simulator only.
    // Deliberately NO iosX64: this dev machine is an Intel Mac, and CMP
    // 1.11.x publishes no iosX64 artifacts anyway, so that target could
    // never resolve dependencies.
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "PiRemote"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:$cmpVersion")
            implementation("org.jetbrains.compose.foundation:foundation:$cmpVersion")
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            implementation("org.jetbrains.compose.ui:ui:$cmpVersion")
            implementation("org.jetbrains.compose.ui:ui-backhandler:$cmpVersion")
            implementation(compose.components.resources)
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
            implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.43.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            implementation("io.ktor:ktor-client-core:3.3.3")
            implementation("io.ktor:ktor-client-websockets:3.3.3")
        }
        androidMain.dependencies {
            implementation("org.jetbrains.compose.ui:ui-tooling:$cmpVersion")
            implementation("org.jetbrains.compose.ui:ui-tooling-preview:$cmpVersion")

            implementation("androidx.core:core-ktx:1.19.0")
            implementation("androidx.activity:activity-compose:1.13.0")
            implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
            implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
            implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
            implementation("androidx.datastore:datastore-preferences:1.2.1")
            implementation("io.ktor:ktor-client-okhttp:3.3.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
            implementation("com.journeyapps:zxing-android-embedded:4.3.0")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.3.3")
            // DataStore ships native artifacts, so the same settings store works
            // on iOS (PreferenceDataStoreFactory.createWithPath, Platform.ios.kt).
            implementation("androidx.datastore:datastore-preferences:1.2.1")
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation("junit:junit:4.13.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.piremote"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.piremote"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.4.0"
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

compose {
    resources {
        // Make the generated Res class (and its accessors) public so the
        // commonMain UI code can read Res.string.* from the same module.
        publicResClass = true
    }
}
