plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Android target via Google's KMP-library plugin (ADR-017). Applied right after the Kotlin
    // Multiplatform plugin so the `android {}` target is available inside the `kotlin {}` block.
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// The Android platform-I/O module: CameraX + ML Kit wiring on top of the platform-agnostic
// mrz-camera-core contract (ADR-021). Since the analyse-frame core, the scan() engine, and the
// MrzCameraScanner interface now live in mrz-camera-core, this module is Android-only — it declares
// no jvm() target (the host tests moved with the contract to mrz-camera-core, which runs them on the
// JVM `check` runner). Intentionally NOT published in this slice; coordinates and the BOM entry are a
// 0.2.0-release-slice concern, so maven-publish and dokka are deliberately not applied yet.

kotlin {
    explicitApi()

    jvmToolchain(21)

    // Android target. compileSdk tracks the latest stable API (36); minSdk 23 per ADR-018. The
    // androidMain CameraX / ML Kit code compiles here and is verified by the android-compile CI job
    // (a Linux `check` runner has no Android SDK, so it cannot compile this target).
    android {
        namespace = "io.lightine.tessera.mrz.camera"
        compileSdk = 36
        minSdk = 23
        // Enables the androidHostTest source set — local JVM unit tests for this Android-only module
        // (the KMP-library plugin's host-test target). classifyCameraState is a pure function over the
        // androidx CameraState int codes, so its test runs on the JVM with no device — the Android
        // counterpart of mrz-camera-ios's AVCaptureMrzScannerErrorMappingTest, run by the android CI job.
        withHostTest {}
    }

    sourceSets {
        androidMain {
            dependencies {
                // The platform-agnostic contract: CameraXMrzScanner implements MrzCameraScanner and
                // MlKitMrzTextRecognizer implements MrzTextRecognizer<ImageProxy>, both from
                // mrz-camera-core, and the scanner's results expose core types — so the core is `api`,
                // seen transitively (it re-exports types / mrz-core / telemetry / coroutines in turn).
                api(project(":mrz-camera-core"))
                // CameraX camera-core supplies the ImageProxy frame type the Android recognizer reads;
                // ML Kit text-recognition is the bundled-model variant — the Latin recognition model
                // ships in the app, so OCR needs no runtime model download or network. (It still pulls
                // the Play Services ML Kit libraries and Google's datatransport stack transitively; the
                // SDK initializes none of it — see docs/reading-risks.md for that dependency surface.)
                implementation(libs.androidx.camera.core)
                implementation(libs.mlkit.text.recognition)
                // camera-lifecycle supplies ProcessCameraProvider + bindToLifecycle for the owns-session
                // scanner; coroutines-android supplies Dispatchers.Main (CameraX binds on the main thread).
                implementation(libs.androidx.camera.lifecycle)
                implementation(libs.kotlinx.coroutines.android)
                // The Camera2 backend CameraX selects at runtime on a device — needed to actually open a
                // camera, but it has no compile surface here, so it stays off the compile classpath.
                runtimeOnly(libs.androidx.camera.camera2)
            }
        }
        // getByName (not the typed accessor): the androidHostTest source set is registered by
        // withHostTest {} above, but the Kotlin-DSL typed accessor is not generated on the same run that
        // first enables it, so reference it by name.
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
        }
    }
}

// --- ML Kit bundled-model guard (docs/open-questions.md "CI and repository hardening", item 6) ---
// ML Kit text-recognition must stay the BUNDLED model: the recognition model ships inside the app, so
// OCR is fully offline with no Play Services model download — the privacy/offline posture a document
// SDK needs (see the androidMain dependency comment above and docs/reading-risks.md).
//
// The discriminator is NOT the absence of the Play Services recognizer
// (com.google.android.gms:play-services-mlkit-text-recognition): that is a TRANSITIVE dependency of the
// bundled artifact and is present on the graph either way. The bundled model is identified by the
// presence of com.google.mlkit:text-recognition-bundled-common, which appears only when the model is
// linked into the app. If a version bump or transitive change silently switched us to the unbundled
// (Play-Services-delivered) recognizer, that marker would vanish and this task fails. Belt-and-
// suspenders: the pinned catalog version already constrains it; this makes the constraint enforced.
//
// Resolves androidRuntimeClasspath, so it needs the Android SDK present — it runs in the android-compile
// CI job (and locally), not the SDK-less JVM `check` runner.
val verifyMlKitBundledModel by tasks.registering {
    group = "verification"
    description =
        "Fails if ML Kit's bundled text-recognition model (text-recognition-bundled-common) " +
        "is not on the Android runtime classpath."
    doLast {
        val marker = "com.google.mlkit:text-recognition-bundled-common"
        val present =
            configurations
                .getByName("androidRuntimeClasspath")
                .incoming.resolutionResult.allComponents
                .any { component -> component.moduleVersion?.let { "${it.group}:${it.name}" } == marker }
        if (!present) {
            throw GradleException(
                "ML Kit bundled-model guard failed: '$marker' is not on androidRuntimeClasspath. " +
                    "The text-recognition model must ship bundled in the app (offline OCR, no Play " +
                    "Services model download); something switched to the unbundled, Play-Services-" +
                    "delivered recognizer. See docs/open-questions.md \"CI and repository hardening\" item 6.",
            )
        }
        logger.lifecycle("ML Kit bundled-model guard: '$marker' present on androidRuntimeClasspath (OK).")
    }
}
