pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // The Android Gradle Plugin — including the com.android.kotlin.multiplatform.library plugin
        // that adds the Android target to the core modules (ADR-017) — is published only to Google's
        // Maven repository, not to mavenCentral or the Gradle Plugin Portal.
        //
        // Content-filtered to the Android/Google coordinate groups only, so Google's Maven cannot
        // shadow any other group's artifacts (supply-chain hygiene — mavenCentral stays the default
        // for everything else). The patterns cover AGP (com.android.*), androidx, and Google
        // libraries including ML Kit (com.google.*).
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Android target dependencies (AGP's own runtime artifacts, androidx — including CameraX's
        // camera-core — and Google's ML Kit) resolve from Google's Maven repository. Content-filtered
        // to the Android/Google coordinate groups (same rationale as pluginManagement above) so it
        // cannot shadow mavenCentral for any other group.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "tessera"

include(":types")
include(":mrz-core")
include(":emrtd-core")
include(":telemetry")
include(":logging")
include(":mrz-camera-core")
include(":mrz-camera-android")
include(":mrz-camera-ios")
include(":mrz-camera-ui-android")
include(":bom")
