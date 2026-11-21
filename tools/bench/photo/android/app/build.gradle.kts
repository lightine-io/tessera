plugins {
    // Keep in step with `agp` in gradle/libs.versions.toml. AGP compiles the Kotlin itself.
    id("com.android.application") version "9.4.1"
    // Pulls AGP's built-in Kotlin up to the version the Tessera modules are built with (2.4), so the
    // app can read their metadata. Keep in step with `kotlin` in gradle/libs.versions.toml.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

android {
    namespace = "io.lightine.tessera.photobench"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.lightine.tessera.photobench"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Substituted by the live Tessera projects through the composite build.
    implementation("io.lightine.tessera:mrz-camera")
    implementation("io.lightine.tessera:mrz-camera-core")
    implementation("io.lightine.tessera:mrz-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // The rescue experiment (RescueExperiment.kt) reads with ML Kit directly: same artifact and version as
    // mrz-camera (`mlkit-text-recognition` in gradle/libs.versions.toml).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // The Compose compiler plugin above wants the runtime on the class path; the app uses no Compose.
    implementation("androidx.compose.runtime:runtime:1.9.0")
}
