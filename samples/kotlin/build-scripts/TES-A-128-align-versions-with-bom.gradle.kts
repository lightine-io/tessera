// Build-script blocks of the page TES-A-128 (https://lightine.youtrack.cloud/articles/TES-A-128).
// Kept as text for the samples check; never compiled or applied. Labels as on the page.

// region TES-A-128 block 1
dependencies {
    implementation(platform("io.lightine.tessera:tessera-bom:0.7.0"))
}
// endregion

// region TES-A-128 block 2
dependencies {
    implementation(platform("io.lightine.tessera:tessera-bom:0.7.0"))
    implementation("io.lightine.tessera:tessera-mrz-core")
    implementation("io.lightine.tessera:tessera-mrz-ocr-core")
}
// endregion
