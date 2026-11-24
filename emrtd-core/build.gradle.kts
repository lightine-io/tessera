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

// Dokka 2 rejects a source file that two source sets both list, and the shared
// src/bouncyCastleMain folder (see below) is compiled into jvmMain and androidMain. Everything in
// it is `internal`, so it never belongs in the API docs: the Android source set drops it from its
// Dokka roots (the JVM one keeps it, still hidden by Dokka's visibility filter). Compilation is
// unchanged. The roots are filled by the Kotlin plugin, so this runs after evaluation.
afterEvaluate {
    dokka {
        dokkaSourceSets.matching { it.name == "androidMain" }.configureEach {
            sourceRoots.setFrom(sourceRoots.files.filterNot { it.path.contains("bouncyCastleMain") })
        }
    }
}

kotlin {
    // ABI tracking (tessera.public-api): declarations marked @InternalTesseraChipApi (public only so
    // Tessera's NFC modules can share them, such as the read coordinator) are left out of the dumps:
    // they are not consumer API and may change in any release.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation.filters {
        exclude {
            annotatedWith.add("io.lightine.tessera.emrtd.InternalTesseraChipApi")
        }
    }

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
    //
    // The chip cryptography on iOS is OpenSSL's libcrypto (ADR-031), static, through cinterop
    // (src/nativeInterop/cinterop/libcrypto.def). scripts/build-openssl-ios.sh builds it per slice
    // from the pinned, checksum-checked source into build/openssl/<slice>; nothing is committed.
    // The library is embedded in the iOS klibs, so it reaches every framework that links this
    // module. CI downloads the source from OpenSSL's GitHub release; to build offline, set
    // TESSERA_OPENSSL_TARBALL (or -Ptessera.opensslTarball) to a tarball already on disk.
    val openSslScript = rootProject.layout.projectDirectory.file("scripts/build-openssl-ios.sh")
    val openSslTarball =
        providers
            .gradleProperty("tessera.opensslTarball")
            .orElse(providers.environmentVariable("TESSERA_OPENSSL_TARBALL"))
            .orElse("")
    listOf(iosArm64() to "ios-arm64", iosSimulatorArm64() to "ios-simulator-arm64").forEach { (target, slice) ->
        val outDir = layout.buildDirectory.dir("openssl/$slice")
        val buildOpenSsl =
            tasks.register<Exec>("buildOpenSsl${target.name.replaceFirstChar { it.uppercase() }}") {
                group = "build"
                description = "Builds OpenSSL libcrypto for the $slice slice (scripts/build-openssl-ios.sh)."
                // Apple slices build only on macOS, as Kotlin/Native's own iOS tasks do.
                onlyIf { System.getProperty("os.name").startsWith("Mac") }
                inputs.file(openSslScript)
                inputs.property("tarball", openSslTarball)
                outputs.dir(outDir)
                executable = "bash"
                args(openSslScript.asFile.path, slice, outDir.get().asFile.path)
                environment("TESSERA_OPENSSL_TARBALL", openSslTarball.get())
            }
        val libcrypto =
            target.compilations.getByName("main").cinterops.create("libcrypto") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/libcrypto.def"))
                includeDirs(outDir.map { it.dir("include") })
                extraOpts(
                    "-libraryPath",
                    outDir
                        .get()
                        .dir("lib")
                        .asFile.path,
                )
            }
        tasks.named(libcrypto.interopProcessingTaskName).configure {
            dependsOn(buildOpenSsl)
            // The klib embeds libcrypto.a, and the task does not track that file by itself: without
            // this input a rebuilt library (a new minimum iOS version, new options) stays out of the klib.
            inputs.files(outDir.map { it.file("stamp.txt") })
        }
    }

    sourceSets {
        all {
            // The module's own code uses its experimental API freely; callers still opt in.
            languageSettings.optIn("io.lightine.tessera.types.ExperimentalTesseraChipApi")
            languageSettings.optIn("io.lightine.tessera.emrtd.InternalTesseraChipApi")
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
        // The chip cryptography (internal ChipCrypto) on BouncyCastle for JVM and Android. Kotlin
        // does not support a source set shared by only a JVM and an Android target (Kotlin docs,
        // "Hierarchical project structure"), so common code reaches the backend through an `expect
        // fun chipCrypto()` with a one-line `actual` per target, and the one backend file lives in
        // src/bouncyCastleMain, a plain folder compiled into both targets. iosMain's `actual` is the
        // OpenSSL backend (cinterop above). BouncyCastle is `implementation`: none of its types may
        // reach the public API (repo-rules checks the api/ dumps).
        jvmMain {
            kotlin.srcDir("src/bouncyCastleMain/kotlin")
            dependencies { implementation(libs.bouncycastle.bcprov) }
        }
        androidMain {
            kotlin.srcDir("src/bouncyCastleMain/kotlin")
            dependencies { implementation(libs.bouncycastle.bcprov) }
        }
        // The test-time signer for the fake PKI (real signatures with keys made at test time, never
        // committed) is BouncyCastle too: one file in src/bouncyCastleTest, compiled into both
        // host test sets the same way. iosTest's `actual` signs with OpenSSL, so nothing skips there.
        jvmTest { kotlin.srcDir("src/bouncyCastleTest/kotlin") }
        matching { it.name == "androidHostTest" }.configureEach { kotlin.srcDir("src/bouncyCastleTest/kotlin") }
        // The scripted chip (fake chip, fake PKI, test signer interface): a plain folder of test
        // sources, compiled into this module's tests and into the tests of emrtd-nfc and
        // emrtd-nfc-ios, which drive whole reads through their entry points against it (TES-346).
        // Test sources only: no published artifact carries it.
        commonTest { kotlin.srcDir("src/chipFixtures/kotlin") }
        iosTest { kotlin.srcDir("src/chipFixturesIos/kotlin") }
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
