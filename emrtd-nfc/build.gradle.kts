plugins {
    id("tessera.android-library")
    id("tessera.public-api")
    id("tessera.kmp-publishing")
}

// The Android NFC module for chip reading (ADR-031): a thin transport that carries command bytes
// between the shared chip code in emrtd-core and an Android IsoDep tag. It holds no chip protocol
// code. Android-only, in the shape of mrz-camera (ADR-021): no jvm() target, a published AAR, and
// its host tests run in the android-compile CI job. The whole chip API is experimental: every
// public declaration carries @ExperimentalTesseraChipApi.

mavenPublishing {
    coordinates(group.toString(), "tessera-emrtd-nfc", version.toString())

    pom {
        name.set("tessera-emrtd-nfc")
        description.set(
            "Android NFC transport for chip reading in the Tessera identity-document SDK " +
                "(experimental): IsoDepTransport carries command bytes between the shared chip code " +
                "(tessera-emrtd-core) and an Android IsoDep tag. Headless: the app owns the NFC " +
                "reader-mode setup and any UI.",
        )
    }
}

kotlin {
    android {
        namespace = "io.lightine.tessera.emrtd.nfc"
        // androidHostTest: local JVM unit tests for this Android-only module. They run against a fake
        // IsoDep channel, with no device.
        withHostTest {}
    }

    sourceSets {
        all {
            languageSettings.optIn("io.lightine.tessera.types.ExperimentalTesseraChipApi")
            // The read coordinator in emrtd-core, public only for Tessera's own NFC modules.
            languageSettings.optIn("io.lightine.tessera.emrtd.InternalTesseraChipApi")
        }
        androidMain {
            dependencies {
                // IsoDepTransport implements emrtd-core's ApduTransport, and its errors are types'
                // NfcError, so the core is an `api` dependency (it re-exports types and coroutines).
                api(project(":emrtd-core"))
            }
        }
        // By name: the typed accessor is not generated on the run that first enables the host test.
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // android.nfc.TagLostException must be constructible in a host test.
            implementation(libs.robolectric)
        }
    }
}
