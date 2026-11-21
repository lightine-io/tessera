plugins {
    id("tessera.android-library")
    id("tessera.public-api")
    id("tessera.kmp-publishing")
}

// The platform-agnostic chip-reading core (ADR-031): all chip protocol code in shared Kotlin. The
// platform modules (emrtd-nfc on Android, the iOS NFC layer) add only a thin transport through the
// public ApduTransport seam, the shape of the camera modules (ADR-021). The whole chip API is
// experimental: every public declaration carries @ExperimentalTesseraChipApi (types), which is a
// compile error for callers that do not opt in. The ABI dumps still track those declarations, so
// every change to them shows in review.

mavenPublishing {
    coordinates(group.toString(), "tessera-emrtd-core", version.toString())

    pom {
        name.set("tessera-emrtd-core")
        description.set(
            "Platform-agnostic chip-reading core for the Tessera identity-document SDK (experimental): " +
                "the ApduTransport seam a platform NFC module implements, the chip access key, and the " +
                "chip protocol code. The Android and iOS NFC modules build on it.",
        )
    }
}

kotlin {
    // The JVM target carries the host tests for this platform-agnostic core, so they run on the
    // JVM `check` runner with no Android SDK and no device. It is also a published artifact
    // (`tessera-emrtd-core-jvm`, listed in the BOM): a JVM app can read a chip through a USB
    // reader of its own with an ApduTransport.
    jvm()

    // Android target (compileSdk and minSdk: tessera.android-library).
    android {
        namespace = "io.lightine.tessera.emrtd"
        // Run the common test suite against the Android target on the JVM host (androidHostTest),
        // executed by the android-compile CI job.
        withHostTest {}
    }

    // iOS targets (ADR-017): device arm64 plus the Apple-Silicon simulator. These shortcuts
    // activate Kotlin's default hierarchy template (shared iosMain/iosTest); the module declares no
    // custom intermediate source set, so the template applies as is. The host tests also run on
    // iosSimulatorArm64 (the ios-compile CI job).
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        all {
            // The module's own code uses its experimental API freely; callers still opt in.
            languageSettings.optIn("io.lightine.tessera.types.ExperimentalTesseraChipApi")
        }
        commonMain {
            dependencies {
                // The chip errors and the experimental marker (types), MrzParser and MrzDocument for
                // the chip's DG1 (mrz-core), and TelemetrySink (telemetry) surface in this module's
                // public API, so they are `api` dependencies.
                api(project(":types"))
                api(project(":mrz-core"))
                api(project(":telemetry"))
                // ApduTransport.transceive is a suspend function, so coroutines is part of the
                // public API.
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                // runTest drives the suspend transport against the fake chip on the host.
                implementation(libs.kotlinx.coroutines.test)
                // Property and fuzz tests: hostile TLV and DER inputs fail with typed errors.
                implementation(libs.kotest.property)
            }
        }
    }
}
