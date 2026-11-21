// The OCR benchmark's Android reader (TES-149): a tiny app that runs ML Kit text recognition on
// the benchmark images on an emulator. A standalone build, not part of the Tessera build:
// nothing here ships.

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

rootProject.name = "ocr-bench-android"

include(":app")
