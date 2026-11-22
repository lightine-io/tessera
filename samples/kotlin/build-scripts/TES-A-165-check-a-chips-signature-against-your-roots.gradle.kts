// Build-script blocks of the page TES-A-165 (https://lightine.youtrack.cloud/articles/TES-A-165).
// Kept as text for the samples check; never compiled or applied. Labels as on the page.

// region TES-A-165 block 1
dependencies {
    implementation(platform("io.lightine.tessera:tessera-bom:1.0.0"))
    implementation("io.lightine.tessera:tessera-emrtd-core")
}

kotlin {
    compilerOptions {
        optIn.add("io.lightine.tessera.types.ExperimentalTesseraChipApi")
    }
}
// endregion
