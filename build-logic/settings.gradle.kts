// Tessera's convention plugins (TES-269): the build settings every module shares, in one
// included build, the shape Gradle recommends for sharing build logic. The root
// settings.gradle.kts includes it with `pluginManagement { includeBuild("build-logic") }`; a
// module applies the plugins by id (`id("tessera.android-library")`, ...).
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // The Android Gradle Plugin is published only to Google's Maven repository. Content-filtered
        // to the Android/Google groups, as in the root settings.gradle.kts.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
    // The root build's version catalog, so the plugin versions stay pinned in one place.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
