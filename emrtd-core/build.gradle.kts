plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Android target via Google's KMP-library plugin (ADR-017). Applied right after the Kotlin
    // Multiplatform plugin so the `android {}` target is available inside the `kotlin {}` block.
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // dokka must be applied before maven.publish: signAllPublications() (root build.gradle.kts)
    // forces eager realization of the Dokka javadoc jar during maven.publish's apply, which looks
    // up the `dokkaGeneratePublicationHtml` task — so that task must already exist.
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

mavenPublishing {
    coordinates(group.toString(), "tessera-emrtd-core", version.toString())

    pom {
        name.set("tessera-emrtd-core")
        description.set(
            "Placeholder module reserving the tessera-emrtd-core artifactId. Will contain pure logic " +
                "for electronic document data (data-group parsing, Security Object structural parsing, " +
                "BAC/PACE key derivation); NFC I/O lives in separate platform-specific modules " +
                "(tessera-emrtd-nfc-android, tessera-emrtd-nfc-ios) per ADR-016. Planned for the 0.6.0 " +
                "release; currently empty, do not depend on this module yet.",
        )
    }
}

kotlin {
    jvmToolchain(21)

    jvm()

    // Android target. compileSdk tracks the latest stable API (37, Android 17 — ADR-017); minSdk 23 per ADR-018.
    // namespace scopes the generated AAR manifest package.
    android {
        namespace = "io.lightine.tessera.emrtd"
        compileSdk = 37
        minSdk = 23
    }

    // No iOS (Kotlin/Native) targets while this module is an empty placeholder: a module with no
    // source produces no `.klib`, so publishing its iOS artifact fails
    // (generateMetadataFileForIosArm64Publication → FileNotFoundException on the missing klib). The
    // iOS targets are added back with this module's first real source — the same "add platform support
    // when the code lands" policy used for explicitApi()/abiValidation. See ADR-017.

    // No sourceSets block while this module is an empty placeholder: there is no source to depend
    // on anything, so declaring dependencies (the `api(:types)` this once carried, test libraries)
    // only pollutes the published POM and the test classpath. Dependencies return with the first
    // real source — the same "when the code lands" policy as the iOS targets / explicitApi /
    // abiValidation above.
}
