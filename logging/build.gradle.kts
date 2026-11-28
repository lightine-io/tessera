plugins {
    id("tessera.android-library")
    id("tessera.public-api")
    id("tessera.kmp-publishing")
}

mavenPublishing {
    coordinates(group.toString(), "tessera-logging", version.toString())

    pom {
        name.set("tessera-logging")
        description.set(
            "Opt-in helper for Tessera: a telemetry sink that prints each SDK diagnostic event to " +
                "the platform log, for debugging.",
        )
    }
}

kotlin {
    jvm()

    // Android target (compileSdk and minSdk: tessera.android-library). namespace scopes the generated AAR
    // manifest package.
    android {
        namespace = "io.lightine.tessera.logging"
    }

    // No iOS (Kotlin/Native) targets, by decision: on iOS the logging helper is a separate Swift product in
    // the Swift package (tessera-swift), so the iOS framework stays free of log-writing code. Do not add
    // iOS targets here.

    sourceSets {
        commonMain {
            dependencies {
                api(project(":telemetry"))
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
