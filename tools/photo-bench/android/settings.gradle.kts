// The full-page photo benchmark's Android reader (TES-131): a tiny app that runs the SDK's own
// saved-image reading (SavedImageMrzReader over mlKitSavedImageRecognizer) on the benchmark photos
// on an emulator. A standalone build, not part of the Tessera build: nothing here ships. It uses
// the live mrz-camera through a composite build.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "photo-bench-android"

includeBuild("../../..")

include(":app")
