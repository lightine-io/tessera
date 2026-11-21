plugins {
    alias(libs.plugins.spotless)
    // Declared with `apply false` so every module loads these plugins from the same class loader. The
    // modules apply them through the convention plugins in build-logic/ (TES-269), which depend on the
    // same catalog versions. The Android target uses Google's KMP-library plugin (ADR-017).
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.maven.publish) apply false
    // Applied here (not `apply false`) for the one aggregated API docs site below (TES-39); the
    // modules apply the same plugin through tessera.kmp-publishing.
    alias(libs.plugins.dokka)
}

allprojects {
    group = "io.lightine.tessera"
}

// One API docs site for every published module (TES-39). `./gradlew dokkaGenerate` writes it to
// build/dokka/html. Aggregating the modules in one Dokka run lets a reference in one module
// (for example a `types` KDoc pointing at an `mrz-core` class) resolve to a real link; the
// per-module javadoc jars for Maven Central are separate and unchanged. The list is the published
// modules: add a new published module here too. `mrz-camera-ios` ships by Swift Package only.
dokka {
    dokkaPublications.html {
        moduleName.set("Tessera")
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
    }
}

dependencies {
    dokka(project(":types"))
    dokka(project(":mrz-core"))
    dokka(project(":emrtd-core"))
    dokka(project(":emrtd-nfc"))
    dokka(project(":emrtd-pcsc"))
    dokka(project(":telemetry"))
    dokka(project(":logging"))
    dokka(project(":mrz-camera-core"))
    dokka(project(":mrz-camera"))
    dokka(project(":mrz-camera-ui"))
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
