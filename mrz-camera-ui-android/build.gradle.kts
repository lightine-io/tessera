plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Android target via Google's KMP-library plugin (ADR-017), same as the other Android modules.
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // The Compose compiler (org.jetbrains.kotlin.plugin.compose), pinned to the Kotlin version. Under the
    // KMP-AGP library plugin there is no `buildFeatures.compose` flag (that belongs to the application /
    // library AGP plugins); the compiler is wired in by applying this plugin, which enables Compose for
    // every Kotlin compilation in the module — here the single Android target and its host tests
    // (ADR-026 / the 0.5.0 tech-stack recap).
    alias(libs.plugins.compose.compiler)
    // dokka before maven.publish (see mrz-core's note) — the javadoc jar is realized during publish apply.
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

// The default Android UI module (0.5.0, ADR-002 / ADR-026): an optional, out-of-the-box Jetpack Compose
// scanner screen layered over the headless reading APIs. A SEPARATE module — not a package inside
// mrz-camera-android — so headless consumers never transitively pull the Compose runtime; dependency
// cleanliness is the Principle-11 promotion trigger that justifies a standalone module. Android-only
// (no jvm() target): Compose is an Android-target concern, so like mrz-camera-android its unit tests run
// on the JVM host via the KMP-library plugin's androidHostTest. Published to Maven Central as
// `tessera-mrz-camera-ui-android` (ADR-016 lockstep versioning); iOS ships its UI as Swift source in
// the tessera-swift package instead (ADR-026 — the reserved mrz-camera-ui-ios module is retired).
//
// SCAFFOLD SLICE: this establishes the module, its published shape, and the frozen public surface (one
// entry composable + one config + one result callback, everything else internal). The live camera
// preview (CameraXViewfinder fed by the scanner's additive SurfaceRequest seam) and the full screen set
// land in the following 0.5.0 slices. ADR-007 freezes the public API at the 0.5.0 *tag*, not here, so
// the surface below still evolves through the release cycle.

mavenPublishing {
    coordinates(group.toString(), "tessera-mrz-camera-ui-android", version.toString())

    pom {
        name.set("tessera-mrz-camera-ui-android")
        description.set(
            "Optional default Jetpack Compose UI for Android MRZ scanning in the Tessera " +
                "identity-document SDK: a ready-made scanner screen (live camera, saved image, and " +
                "manual entry per consumer configuration) built over the headless " +
                "tessera-mrz-camera-android reading APIs. Consumers who supply their own UI do not " +
                "depend on this module.",
        )
    }
}

kotlin {
    explicitApi()

    // ABI tracking (ADR-007), identical to mrz-camera-android: Kotlin's abiValidation does not dump the
    // AGP-KMP `android {}` target, so `checkKotlinAbi` is vacuous here. The android-only public surface
    // (MrzScannerScreen + MrzScannerConfig + MrzScannerResult) is gated by `checkAndroidAbi` — the
    // root-build convention that dumps this target's compiled bytecode with binary-compatibility-validator
    // into the committed api/android/mrz-camera-ui-android.api and fails on an undeclared change (TES-35).
    // Refresh after an intended change: `./gradlew :mrz-camera-ui-android:updateAndroidAbi`. abiValidation()
    // stays enabled so a Kotlin-native baseline also appears the moment KGP gains android-target dumping.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    jvmToolchain(21)

    // Android target. compileSdk tracks the latest stable API (37, Android 17 — ADR-017), like every
    // module; minSdk 23 per ADR-018. (This module's Compose stack — lifecycle-runtime-compose 2.11.0 —
    // independently requires compileSdk 37 via its AAR minCompileSdk.)
    android {
        namespace = "io.lightine.tessera.mrz.camera.ui"
        compileSdk = 37
        minSdk = 23
        // Process this module's res/ and generate its R class (the KMP-library plugin leaves resource
        // handling off by default). This backs the tessera_* string-resource overlay contract (TES-46):
        // MrzScannerScreen reads its user-facing copy via stringResource(R.string.tessera_scanner_*), and a
        // consumer overrides any label by redefining the same key (Android resource merge prefers the app's
        // value). The key set is public and freezes at the 0.5.0 tag — see res/values/strings.xml.
        androidResources {
            enable = true
        }
        // androidHostTest carries the module's unit + Compose UI tests, run on the JVM host (no device).
        // isIncludeAndroidResources merges this module's res/ into the host-test classpath so a
        // Robolectric-driven composable can resolve android resources (e.g. the tessera_* strings above) —
        // the tech-stack recap's host-side Compose testing shape.
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        androidMain {
            dependencies {
                // The reading contract: MrzScannerResult surfaces mrz-camera-core's MrzScanResult in its
                // public API, so the core is `api` (seen by consumers).
                api(project(":mrz-camera-core"))
                // The Android owns-the-session scanner drives the live camera behind the screen; the UI
                // arms its opt-in preview seam and renders the SurfaceRequest it publishes. `implementation`
                // — the scanner is an internal engine of the screen, not part of this module's public API
                // (the frozen surface is MrzScannerScreen + config + result only).
                implementation(project(":mrz-camera-android"))
                // compose-runtime is `api` — the public entry point is a `@Composable`, so a consumer's
                // own compilation needs the Compose runtime on its classpath. The rest of Compose is an
                // internal implementation detail of the screens.
                api(libs.androidx.compose.runtime)
                implementation(libs.androidx.compose.ui)
                implementation(libs.androidx.compose.foundation)
                implementation(libs.androidx.compose.material3)
                // camera-compose supplies the CameraXViewfinder composable that draws the scanner's live
                // preview SurfaceRequest — the live-preview slice (0.5.0). SurfaceRequest itself arrives
                // transitively via mrz-camera-android's `api(camera-core)`.
                implementation(libs.androidx.camera.compose)
                // lifecycle-runtime-compose supplies collectAsStateWithLifecycle for hoisting scanner
                // state (the SurfaceRequest and per-frame results) off the camera thread into Compose;
                // part of the module's Compose stack and the reason it compiles against API 37 (see the
                // compileSdk note above).
                implementation(libs.androidx.lifecycle.runtime.compose)
            }
        }
        // getByName (not the typed accessor): the androidHostTest source set is registered by
        // withHostTest {} above, but the Kotlin-DSL typed accessor is not generated on the same run that
        // first enables it, so reference it by name.
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            // Host-side Compose UI testing: the compose test rule + semantics matchers, the test-only
            // manifest entry for the rule's host ComponentActivity, and Robolectric's JVM Android runtime.
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.compose.ui.test.manifest)
            implementation(libs.robolectric)
        }
    }
}
