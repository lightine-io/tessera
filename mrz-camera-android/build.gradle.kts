plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Android target via Google's KMP-library plugin (ADR-017). Applied right after the Kotlin
    // Multiplatform plugin so the `android {}` target is available inside the `kotlin {}` block.
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // dokka before maven.publish (see mrz-core's note) — the javadoc jar is realized during publish apply.
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

// The Android platform-I/O module: CameraX + ML Kit wiring on top of the platform-agnostic
// mrz-camera-core contract (ADR-021). Since the analyse-frame core, the scan() engine, and the
// MrzCameraScanner interface now live in mrz-camera-core, this module is Android-only — it declares
// no jvm() target (the host tests moved with the contract to mrz-camera-core, which runs them on the
// JVM `check` runner). Published to Maven Central at the 0.2.0 release as `tessera-mrz-camera-android`
// (ADR-016/ADR-021) — the Android distribution of the camera scanner (iOS ships via SPM, ADR-019).

mavenPublishing {
    coordinates(group.toString(), "tessera-mrz-camera-android", version.toString())

    pom {
        name.set("tessera-mrz-camera-android")
        description.set(
            "Android camera MRZ reading for the Tessera identity-document SDK: the CameraX " +
                "owns-the-session scanner (CameraXMrzScanner) and the bundled-model ML Kit OCR seam, " +
                "built on the platform-agnostic tessera-mrz-camera-core contract. Headless — the " +
                "consumer supplies any UI.",
        )
    }
}

kotlin {
    explicitApi()

    // Opts in to mrz-camera-core's @InternalTesseraApi: declarations public only so Tessera's camera modules
    // can share them (the short-row rescue rules, MrzRowRescue). Not consumer API; consumers never opt in.
    compilerOptions {
        optIn.add("io.lightine.tessera.mrz.camera.InternalTesseraApi")
    }

    // ABI tracking (ADR-007). Since Kotlin 2.4.20 (KT-85950) abiValidation dumps the AGP-KMP `android {}`
    // target too, so this module's android-only public surface (CameraXMrzScanner, MlKitMrzTextRecognizer,
    // the saved-image/EXIF factories) is baselined in api/android/mrz-camera-android.api and gated by
    // `checkKotlinAbi` like every other module. Refresh after an intended change: `./gradlew updateKotlinAbi`.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    jvmToolchain(21)

    // Android target. compileSdk tracks the latest stable API (37, Android 17 — ADR-017); minSdk 23 per ADR-018. The
    // androidMain CameraX / ML Kit code compiles here and is verified by the android-compile CI job
    // (a Linux `check` runner has no Android SDK, so it cannot compile this target).
    android {
        namespace = "io.lightine.tessera.mrz.camera"
        compileSdk = 37
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
                // CameraX camera-core supplies the ImageProxy frame type the Android recognizer reads and
                // the SurfaceRequest type exposed on CameraXMrzScanner.surfaceRequest (the opt-in live-
                // preview seam, 0.5.0) — a public return type, so camera-core is `api`, seen transitively
                // by the viewfinder in mrz-camera-ui-android and any consumer that renders the preview.
                api(libs.androidx.camera.core)
                // ML Kit text-recognition is the bundled-model variant — the Latin recognition model
                // ships in the app, so OCR needs no runtime model download or network. (It still pulls
                // the Play Services ML Kit libraries and Google's datatransport stack transitively; the
                // SDK initializes none of it - see the KB page "What each reading method proves" for that dependency surface.)
                implementation(libs.mlkit.text.recognition)
                // camera-lifecycle supplies ProcessCameraProvider + bindToLifecycle for the owns-session
                // scanner; coroutines-android supplies Dispatchers.Main (CameraX binds on the main thread).
                implementation(libs.androidx.camera.lifecycle)
                implementation(libs.kotlinx.coroutines.android)
                // androidx.exifinterface reads a saved image's EXIF for the CaptureMetadataReader (0.3.0).
                implementation(libs.androidx.exifinterface)
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
            // Test-only (TES-205): Robolectric gives the host test a real Android main thread (the main
            // Looper behind Dispatchers.Main), so CameraXMrzScannerThreadingTest can prove results arrive
            // on main and recognition runs off it. Same catalog entry mrz-camera-ui-android's host tests use.
            implementation(libs.robolectric)
        }
    }
}

// --- ML Kit bundled-model guard (the project's issue tracker "CI and repository hardening", item 6) ---
// ML Kit text-recognition must stay the BUNDLED model: the recognition model ships inside the app, so
// OCR is fully offline with no Play Services model download — the privacy/offline posture a document
// SDK needs (see the androidMain dependency comment above).
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
// CI job (and locally), not the SDK-less JVM `check` runner. The resolution result is captured as a
// Provider at configuration time (not looked up inside doLast), so the task action holds no reference to
// the build script — a configuration-cache requirement; the graph itself is still resolved only when the
// task runs.
tasks.register("verifyMlKitBundledModel") {
    group = "verification"
    description =
        "Fails if ML Kit's bundled text-recognition model (text-recognition-bundled-common) " +
        "is not on the Android runtime classpath."
    val runtimeGraphRoot =
        configurations.named("androidRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
    doLast {
        val marker = "com.google.mlkit:text-recognition-bundled-common"
        // Walk the resolved graph from its root; a visited set guards against dependency cycles.
        val visited = HashSet<String>()
        val queue = ArrayDeque(listOf(runtimeGraphRoot.get()))
        var present = false
        while (queue.isNotEmpty() && !present) {
            val component = queue.removeFirst()
            if (!visited.add(component.id.displayName)) continue
            present = component.moduleVersion?.let { "${it.group}:${it.name}" } == marker
            component.dependencies
                .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                .forEach { queue.addLast(it.selected) }
        }
        if (!present) {
            throw GradleException(
                "ML Kit bundled-model guard failed: '$marker' is not on androidRuntimeClasspath. " +
                    "The text-recognition model must ship bundled in the app (offline OCR, no Play " +
                    "Services model download); something switched to the unbundled, Play-Services-" +
                    "delivered recognizer. See the project's issue tracker \"CI and repository hardening\" item 6.",
            )
        }
        logger.lifecycle("ML Kit bundled-model guard: '$marker' present on androidRuntimeClasspath (OK).")
    }
}
