// Build-script blocks of the page TES-A-92 (https://lightine.youtrack.cloud/articles/TES-A-92).
// Kept as text for the samples check; never compiled or applied. Labels as on the page.

// region TES-A-92 block 1
dependencies {
    implementation(platform("io.lightine.tessera:tessera-bom:1.0.0"))
    implementation("io.lightine.tessera:tessera-mrz-camera-ui")
    implementation("androidx.activity:activity-compose:1.13.0")
}
// endregion
