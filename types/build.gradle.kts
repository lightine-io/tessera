plugins {
    id("tessera.android-library")
    id("tessera.public-api")
    id("tessera.kmp-publishing")
}

mavenPublishing {
    coordinates(group.toString(), "tessera-types", version.toString())

    pom {
        name.set("tessera-types")
        description.set(
            "Core type definitions and error taxonomy shared across the Tessera identity-document SDK. " +
                "Contains the cross-cutting vocabulary (document type codes, sex characters, unmapped-character " +
                "representations) and the MRZ error hierarchy. Typically pulled in transitively via tessera-mrz-core.",
        )
    }
}

kotlin {
    jvm()

    // Android target (compileSdk and minSdk: tessera.android-library). namespace scopes the generated AAR
    // manifest package.
    android {
        namespace = "io.lightine.tessera.types"
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
        commonMain {
            dependencies {
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
