plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Android target via Google's KMP-library plugin (ADR-017). Applied right after the Kotlin
    // Multiplatform plugin so the `android {}` target is available inside the `kotlin {}` block.
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// The platform-agnostic camera-reading core (ADR-021): the analyse-frame engine, the streaming
// scan() contract, the MrzCameraScanner interface, and the shared result/error/quality types.
// mrz-camera-android and mrz-camera-ios are thin platform-I/O modules that depend on this; the
// dependency-free seam (MrzTextRecognizer<F>) is also where a future USB/desktop/web frame source
// plugs in (ADR-020). Intentionally NOT published in this slice — like mrz-camera-android, this
// module's Maven coordinates and BOM entry are a 0.2.0-release-slice concern, so the vanniktech
// maven-publish and dokka plugins are deliberately not applied yet.

kotlin {
    explicitApi()

    jvmToolchain(21)

    // The JVM target carries the host tests for this platform-agnostic core, so they run on the
    // existing JVM `check` CI runner with no Android SDK and no device. It is a build/test target,
    // not a declared publication shape — what this module publishes is decided at the release slice.
    jvm()

    // Android target. compileSdk tracks the latest stable API (37); minSdk 23 per ADR-018. The
    // namespace is distinct from mrz-camera-android's (each module's AAR manifest needs its own),
    // while the Kotlin package stays io.lightine.tessera.mrz.camera across both — Android's namespace
    // and the Kotlin package are independent. Verified by the android-compile CI job.
    android {
        namespace = "io.lightine.tessera.mrz.camera.core"
        compileSdk = 37
        minSdk = 23
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
