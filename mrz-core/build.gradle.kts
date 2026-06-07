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
    explicitApi()

    // ABI tracking — enforces the ADR-007 no-public-API-break promise: snapshots the public API under
    // api/ and fails `check` on an accidental break. Built-in Kotlin Gradle feature (no extra
    // dependency, bundled with Kotlin so it never blocks a Kotlin bump). Refresh: `./gradlew updateKotlinAbi`.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
    }

    jvmToolchain(21)

    jvm()

    // Android target. compileSdk tracks the latest stable API (36); minSdk 23 per ADR-018.
    // namespace scopes the generated AAR manifest package.
    android {
        namespace = "io.lightine.tessera.mrz"
        compileSdk = 36
        minSdk = 23
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
