import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The iOS platform-I/O module: AVFoundation + Apple Vision on top of the platform-agnostic
// mrz-camera-core contract (ADR-021). iOS targets only — no jvm/android target, and (unlike the
// other camera modules) no Android Gradle plugin, since there is no Android target. It is also the
// umbrella for iOS distribution (ADR-019): it assembles the `Tessera` XCFramework, exporting
// mrz-camera-core (and transitively mrz-core / types / telemetry) so a Swift consumer sees the
// result, parse, and error types — not opaque handles. The XCFramework is wrapped in a Swift package
// (a dedicated distribution repo, JetBrains' recommended layout) and the zipped binary is attached to
// the GitHub release; that publication step lands at the 0.2.0-release-slice. Maven publication stays
// off (this module is not on Maven Central — iOS ships only through SPM).

kotlin {
    explicitApi()

    jvmToolchain(21)

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
            // Export each module whose public types appear in the umbrella's Swift-facing surface, so
            // they show in the framework's generated Obj-C/Swift headers rather than as opaque handles:
            // the camera contract (mrz-camera-core), the parser/result types (mrz-core), the shared
            // vocabulary (types), and the telemetry sink (telemetry). Exported explicitly rather than via
            // the experimental transitiveExport — which also tries to export coroutines' internal atomicfu
            // interop klib (not exportable). coroutines itself is intentionally not exported: Flow does not
            // bridge cleanly to Swift yet (tracked in open-questions), so leaving it unexported avoids the
            // atomicfu warning without changing the (already-Kotlin-handle) Swift Flow experience.
            export(project(":mrz-camera-core"))
            export(project(":mrz-core"))
            export(project(":types"))
            export(project(":telemetry"))
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
