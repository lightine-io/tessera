plugins {
    alias(libs.plugins.spotless)
    // Declared with `apply false` so every module loads these plugins from the same class loader. The
    // modules apply them through the convention plugins in build-logic/ (TES-269), which depend on the
    // same catalog versions. The Android target uses Google's KMP-library plugin (ADR-017).
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka) apply false
}

allprojects {
    group = "io.lightine.tessera"
}

// The publishing setup every published module shares (Central Portal, signing, POM metadata,
// the KMP and BOM publication shapes) lives in the convention plugins under build-logic/
// (tessera.publishing, tessera.kmp-publishing), with the other shared module settings (TES-269).

spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    // The documentation samples (samples/kotlin/src, samples/kotlin/build-scripts) are left as the
    // pages print them: a formatter would change the page text the samples check compares (TES-230).
    val pageSamples = arrayOf("samples/kotlin/src/**", "samples/kotlin/build-scripts/**")
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/.gradle/**", *pageSamples)
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "**/.gradle/**", *pageSamples)
        ktlint(ktlintVersion)
    }
}
