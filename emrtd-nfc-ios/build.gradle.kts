plugins {
    id("tessera.kotlin-multiplatform")
    id("tessera.public-api")
}

// The iPhone NFC module for chip reading (ADR-031): a thin transport that carries command bytes
// between the shared chip code in emrtd-core and an ISO 7816 tag from Core NFC, plus a helper that
// runs the system's NFC reader session. It holds no chip protocol code. iOS-only, in the shape of
// mrz-camera-ios (ADR-021): no jvm or Android target and no Android Gradle plugin. Like
// mrz-camera-ios it is not on Maven Central: it ships inside the `Tessera` XCFramework, which
// mrz-camera-ios assembles and exports it from (ADR-019). Kotlin, not Swift: the on-device
// prototype showed that Kotlin/Native reaches every Core NFC call it needs, and typed NfcError
// values stay in Kotlin. The whole chip API is experimental: every public declaration carries
// @ExperimentalTesseraChipApi.

kotlin {
    // iOS targets (ADR-017): device arm64 plus the Apple-Silicon simulator, which activate the
    // default hierarchy template's shared iosMain/iosTest. The simulator has no NFC reader: the
    // tests drive the transport and the session through fakes of the Core NFC objects.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        all {
            languageSettings.optIn("io.lightine.tessera.types.ExperimentalTesseraChipApi")
            // The Core NFC bridge works with C pointers (NSData bytes) and C structs (the OS version).
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain {
            dependencies {
                // CoreNfcTransport implements emrtd-core's ApduTransport, and its errors are types'
                // NfcError, so the core is an `api` dependency (it re-exports types and coroutines).
                api(project(":emrtd-core"))
            }
        }
        // iOS-only tests, so the test dependencies go on iosTest rather than an empty commonTest.
        iosTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
