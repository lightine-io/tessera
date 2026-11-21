// A runnable Android app that opens the ready-made scanner screen and shows the fields of the
// reading it returns (TES-37). It depends on the SDK modules as project dependencies, so it always
// builds against the current sources.
//
// Never published: no tessera.publishing / tessera.kmp-publishing plugin, so it has no publication
// and is not in the BOM. It has no ABI dump (no tessera.public-api plugin).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.lightine.tessera.samples.scanner"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.lightine.tessera.samples.scanner"
        // The scanner screen supports API 23; 24 keeps the sample simple.
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":mrz-core"))
    implementation(project(":mrz-camera-ui"))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
}
