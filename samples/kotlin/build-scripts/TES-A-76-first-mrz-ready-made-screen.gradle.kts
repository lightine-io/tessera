// Build-script blocks of the page TES-A-76 (https://lightine.youtrack.cloud/articles/TES-A-76).
// Kept as text for the samples check; never compiled or applied. Labels as on the page.

// region TES-A-76 block 1
plugins {
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

android {
    buildFeatures {
        compose = true
    }
}
// endregion

// region TES-A-76 block 2
dependencies {
    implementation(platform("io.lightine.tessera:tessera-bom:1.1.0"))
    implementation("io.lightine.tessera:tessera-mrz-camera-ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.4.0")
}
// endregion
