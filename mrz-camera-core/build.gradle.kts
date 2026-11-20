plugins {
    id("tessera.android-library")
    id("tessera.public-api")
    id("tessera.kmp-publishing")
}

// The platform-agnostic camera-reading core (ADR-021): the analyse-frame engine, the streaming
// scan() contract, the MrzCameraScanner interface, and the shared result/error/quality types.
// mrz-camera and mrz-camera-ios are thin platform-I/O modules that depend on this; the
// dependency-free seam (MrzTextRecognizer<F>) is also where a future USB/desktop/web frame source
// plugs in (ADR-020). Published to Maven Central at the 0.2.0 release as `tessera-mrz-camera-core`
// (ADR-016/ADR-021); Android consumers of `tessera-mrz-camera` pull it transitively.

mavenPublishing {
    coordinates(group.toString(), "tessera-mrz-camera-core", version.toString())

    pom {
        name.set("tessera-mrz-camera-core")
        description.set(
            "Platform-agnostic camera MRZ-reading core for the Tessera identity-document SDK: the " +
                "analyse-frame engine, the frame-source-agnostic scan() streaming contract, the " +
                "MrzCameraScanner owns-session interface, and the shared scan-result, camera-error, " +
                "and quality types. The Android and iOS platform-I/O modules build on it.",
        )
    }
}

kotlin {
    // ABI tracking (tessera.public-api): declarations marked @InternalTesseraApi (public only so Tessera's
    // camera modules can share them, e.g. MrzRowRescue) are left out of the dumps: they are not consumer
    // API and may change in any release.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation.filters {
        exclude {
            annotatedWith.add("io.lightine.tessera.mrz.camera.InternalTesseraApi")
        }
    }

    // The JVM target is published as `tessera-mrz-camera-core-jvm` and listed in the BOM: a JVM app
    // uses it to analyze its own frames and to read saved images with its own recognizer. It also
    // carries the host tests for this platform-agnostic core, so they run on the JVM `check` CI
    // runner with no Android SDK and no device. Removing the target would remove a published
    // artifact, which is a breaking change (ADR-007).
    jvm()

    // Android target (compileSdk and minSdk: tessera.android-library). The namespace is distinct from mrz-camera's (each module's AAR manifest needs its own),
    // while the Kotlin package stays io.lightine.tessera.mrz.camera across both — Android's namespace
    // and the Kotlin package are independent. Verified by the android-compile CI job.
    android {
        namespace = "io.lightine.tessera.mrz.camera.core"
        // Run the common test suite against the Android target on the JVM host (androidHostTest) —
        // executed by the android-compile CI job, closing the "Android target is compile-only" gap.
        withHostTest {}
    }

    // iOS targets (ADR-017): device arm64 plus the Apple-Silicon simulator. These two
    // standard shortcuts activate Kotlin's default hierarchy template (shared iosMain/iosTest). This
    // core has no platform code of its own — it is pure commonMain — so there is no iosMain here; the
    // targets exist so mrz-camera-ios can resolve this module for its iOS compilations, and so the
    // host tests also run on iosSimulatorArm64 (the ios-compile CI job).
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            dependencies {
                // ParseResult / MrzParser (mrz-core), the MrzFormat & error vocabulary (types), and
                // TelemetrySink / TelemetryEvent (telemetry) all surface in this module's public API,
                // so they are `api` dependencies — consumers see them transitively.
                api(project(":types"))
                api(project(":mrz-core"))
                api(project(":telemetry"))
                // The owns-session contract exposes Flow<MrzScanResult> (MrzCameraScanner.results) and
                // the scan() streaming engine, so coroutines is part of the public API, not internal.
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                // runTest — exercises the suspend analyse()/scan() functions on the host with a mock
                // recognizer over fake frames (no device, no real OCR).
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
