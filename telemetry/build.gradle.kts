plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Android target via Google's KMP-library plugin (ADR-017). Applied right after the Kotlin
    // Multiplatform plugin so the `android {}` target is available inside the `kotlin {}` block.
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // dokka must be applied before maven.publish: signAllPublications() (root build.gradle.kts)
    // forces eager realization of the Dokka javadoc jar during maven.publish's apply, which looks
    // up the `dokkaGeneratePublicationHtml` task — so that task must already exist.
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
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
    jvmToolchain(21)

    jvm()

    // Android target. compileSdk tracks the latest stable API (36); minSdk 23 per ADR-018.
    // namespace scopes the generated AAR manifest package.
    android {
        namespace = "io.lightine.tessera.telemetry"
        compileSdk = 36
        minSdk = 23
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
