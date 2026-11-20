plugins {
    id("tessera.android-library")
    id("tessera.public-api")
    id("tessera.kmp-publishing")
}

mavenPublishing {
    coordinates(group.toString(), "tessera-telemetry", version.toString())

    pom {
        name.set("tessera-telemetry")
        description.set(
            "Pluggable telemetry interface for Tessera SDK operations. Consumers wire in their own " +
                "telemetry sink; the SDK does no I/O of its own. Target-agnostic and dependency-free.",
        )
    }
}

kotlin {
    jvm()

    // Android target (compileSdk and minSdk: tessera.android-library). namespace scopes the generated AAR
    // manifest package.
    android {
        namespace = "io.lightine.tessera.telemetry"
        // Run the common test suite against the Android target on the JVM host (androidHostTest) —
        // executed by the android-compile CI job, closing the "Android target is compile-only" gap.
        withHostTest {}
    }

    // iOS targets (ADR-017): device arm64 plus the Apple-Silicon simulator. These two
    // standard shortcuts activate Kotlin's default hierarchy template, which provides the shared
    // iosMain/iosTest source sets.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotest.property)
            }
        }
    }
}
