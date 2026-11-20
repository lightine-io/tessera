plugins {
    id("tessera.android-library")
    id("tessera.kmp-publishing")
}

mavenPublishing {
    coordinates(group.toString(), "tessera-logging", version.toString())

    pom {
        name.set("tessera-logging")
        description.set(
            "Placeholder module reserving the tessera-logging artifactId for the Tessera logging " +
                "subsystem. Currently empty; do not depend on this module yet.",
        )
    }
}

kotlin {
    jvm()

    // Android target (compileSdk and minSdk: tessera.android-library). namespace scopes the generated AAR
    // manifest package.
    android {
        namespace = "io.lightine.tessera.logging"
    }

    // No iOS (Kotlin/Native) targets while this module is an empty placeholder: a module with no
    // source produces no `.klib`, so publishing its iOS artifact fails
    // (generateMetadataFileForIosArm64Publication → FileNotFoundException on the missing klib). The
    // iOS targets are added back with this module's first real source — the same "add platform support
    // when the code lands" policy used for explicitApi()/abiValidation. See ADR-017.

    // No sourceSets block while this module is an empty placeholder: there is no source, so test
    // dependencies only pollute the test classpath. Dependencies return with the first real source —
    // the same "when the code lands" policy as the iOS targets / explicitApi / abiValidation above.
}
