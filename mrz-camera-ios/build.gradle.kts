import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("tessera.kotlin-multiplatform")
    id("tessera.public-api")
}

// The iOS platform-I/O module: AVFoundation + Apple Vision on top of the platform-agnostic
// mrz-camera-core contract (ADR-021). iOS targets only — no jvm/android target, and (unlike the
// other camera modules) no Android Gradle plugin, since there is no Android target. It is also the
// umbrella for iOS distribution (ADR-019): it assembles the `Tessera` XCFramework, exporting
// mrz-camera-core (and transitively mrz-core / types / telemetry) so a Swift consumer sees the
// result, parse, and error types — not opaque handles. Since chip reading (TES-321) it also exports
// emrtd-core and the iPhone NFC transport (emrtd-nfc-ios). The umbrella stays here rather than in a
// framework-only module (TES-286): moving it would rename the task the release workflow and the
// tessera-swift scripts call, for no change to what ships. The XCFramework is wrapped in a Swift package
// (a dedicated distribution repo, JetBrains' recommended layout) and the zipped binary is attached to
// the GitHub release; that publication step lands at the 0.2.0-release-slice. Maven publication stays
// off (this module is not on Maven Central — iOS ships only through SPM).

kotlin {
    // Opts in to mrz-camera-core's @InternalTesseraApi: declarations public only so Tessera's camera modules
    // can share them (the short-row rescue rules, MrzRowRescue). Not consumer API; consumers never opt in.
    compilerOptions {
        optIn.add("io.lightine.tessera.mrz.camera.InternalTesseraApi")
    }

    // iOS targets (ADR-017): device arm64 plus the Apple-Silicon simulator. These two
    // standard shortcuts activate Kotlin's default hierarchy template, providing the shared
    // iosMain/iosTest source sets where the AVFoundation/Vision code and its simulator tests live.
    // Each target also produces a static framework binary; XCFramework("Tessera") bundles both slices
    // into one `Tessera.xcframework` (device + Apple-Silicon simulator) via the `assembleTesseraXCFramework`
    // task — the artifact SPM distributes (ADR-019).
    val xcframework = XCFramework("Tessera")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Tessera"
            // Static linkage is the simpler, sign-free shape for an SPM binaryTarget (no embedded
            // dynamic framework to code-sign in the consumer app).
            isStatic = true
            // The framework's Info.plist, from the project version rather than Kotlin/Native's defaults
            // (1.0 / 1 / the Gradle-derived bundle id). The plist version is for tooling; the Swift package
            // is what consumers pin. Apple wants up to three dot-separated integers, so a pre-release
            // suffix in `version` would need trimming here.
            binaryOption("bundleId", "io.lightine.tessera")
            binaryOption("bundleShortVersionString", project.version.toString())
            binaryOption("bundleVersion", project.version.toString())
            // iOS 18 is the minimum (the Swift package already requires it). Kotlin/Native has no DSL for it:
            // the override sets the deployment target the framework is linked for, and so MinimumOSVersion.
            freeCompilerArgs += "-Xoverride-konan-properties=osVersionMin.ios_arm64=18.0;osVersionMin.ios_simulator_arm64=18.0"
            // Export each module whose public types appear in the umbrella's Swift-facing surface, so
            // they show in the framework's generated Obj-C/Swift headers rather than as opaque handles:
            // the camera contract (mrz-camera-core), the parser/result types (mrz-core), the shared
            // vocabulary (types), and the telemetry sink (telemetry). Exported explicitly rather than via
            // the experimental transitiveExport — which also tries to export coroutines' internal atomicfu
            // interop klib (not exportable). coroutines itself is intentionally not exported: Flow does not
            // bridge cleanly to Swift yet (tracked in the issue tracker), so leaving it unexported avoids the
            // atomicfu warning without changing the (already-Kotlin-handle) Swift Flow experience.
            export(project(":mrz-camera-core"))
            export(project(":mrz-core"))
            export(project(":types"))
            export(project(":telemetry"))
            // Chip reading (ADR-031): the shared chip core (ApduTransport, ChipAccessKey, the LDS
            // views, the trust types) and the iPhone NFC transport over Core NFC. Experimental API;
            // Swift has no opt-in, so their documentation says "experimental".
            export(project(":emrtd-core"))
            export(project(":emrtd-nfc-ios"))
            xcframework.add(this)
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                // The platform-agnostic contract: VisionMrzTextRecognizer implements
                // MrzTextRecognizer<CMSampleBufferRef> from mrz-camera-core, and its results expose
                // core types — so the core is `api`, seen transitively (it re-exports types / mrz-core
                // / telemetry / coroutines in turn).
                api(project(":mrz-camera-core"))
                // Not used by the camera code: here only so the umbrella framework can export the
                // chip-reading modules (export needs an api dependency). emrtd-nfc-ios brings
                // emrtd-core with it.
                api(project(":emrtd-nfc-ios"))
            }
        }
        // The tests are iOS-only (they drive Apple Vision on the Simulator), so the test framework
        // dependency goes on iosTest rather than commonTest — this module has no common test sources,
        // and configuring commonTest while leaving it empty trips an "unused source set" warning.
        iosTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// Packages the release Tessera.xcframework into the .zip an SPM `binaryTarget(url:checksum:)` consumes
// (ADR-019). At the 0.2.0 release this zip is attached to the GitHub release and the output of
// `swift package compute-checksum build/distributions/Tessera.xcframework.zip` goes into the
// distribution repo's Package.swift. Wired here — ahead of the release — so the full packaging path
// (assemble → zip) is exercised and reproducible, not improvised at tag time. The zip holds the
// `.xcframework` at its root, the layout SPM expects.
tasks.register<Zip>("packTesseraXCFramework") {
    group = "build"
    description = "Zips the release Tessera.xcframework for SPM binaryTarget distribution (ADR-019)."
    dependsOn("assembleTesseraReleaseXCFramework")
    from(layout.buildDirectory.dir("XCFrameworks/release"))
    include("Tessera.xcframework/**")
    archiveFileName.set("Tessera.xcframework.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}

// Compiles src/frameworkHeaderCheck/TesseraHeaderCheck.m (syntax only, against the iOS Simulator SDK)
// with the generated header of the debug simulator framework. The file names the public camera and
// chip types Swift apps reach through the umbrella, so a type that drops out of the exports, or is
// renamed, fails here. macOS only (xcrun); CI runs it in the iOS job.
tasks.register<Exec>("checkTesseraFrameworkHeader") {
    group = "verification"
    description = "Compiles an Objective-C file that names the umbrella's public types against Tessera.h."
    dependsOn("linkDebugFrameworkIosSimulatorArm64")
    val frameworksDir = layout.buildDirectory.dir("bin/iosSimulatorArm64/debugFramework")
    val source = layout.projectDirectory.file("src/frameworkHeaderCheck/TesseraHeaderCheck.m")
    inputs.dir(frameworksDir)
    inputs.file(source)
    commandLine(
        "xcrun",
        "--sdk",
        "iphonesimulator",
        "clang",
        "-fsyntax-only",
        "-fobjc-arc",
        "-fmodules",
        "-target",
        "arm64-apple-ios18.0-simulator",
        "-F",
        frameworksDir.get().asFile.absolutePath,
        source.asFile.absolutePath,
    )
}
