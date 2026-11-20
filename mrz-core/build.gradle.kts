plugins {
    id("tessera.android-library")
    id("tessera.public-api")
    id("tessera.kmp-publishing")
}

mavenPublishing {
    coordinates(group.toString(), "tessera-mrz-core", version.toString())

    pom {
        name.set("tessera-mrz-core")
        description.set(
            "MRZ (Machine Readable Zone) parsing, validation, and generation for all ICAO Doc 9303 " +
                "document formats — TD1, TD2, TD3, MRV-A, and MRV-B. Reader-not-oracle: extracts data " +
                "verbatim and reports observations; consumers make trust decisions.",
        )
    }
}

kotlin {
    jvm()

    // Android target (compileSdk and minSdk: tessera.android-library). namespace scopes the generated AAR
    // manifest package.
    android {
        namespace = "io.lightine.tessera.mrz"
        // Run the common test suite against the Android target on the JVM host (androidHostTest) —
        // executed by the android-compile CI job, closing the "Android target is compile-only" gap.
        withHostTest {}
    }

    // iOS targets (ADR-017): device arm64 plus the Apple-Silicon simulator. These two
    // standard shortcuts activate Kotlin's default hierarchy template, which provides the shared
    // iosMain/iosTest source sets — where the iOS Normalization actual lives.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":types"))
                api(libs.kotlinx.datetime)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotest.property)
            }
        }
    }
}
