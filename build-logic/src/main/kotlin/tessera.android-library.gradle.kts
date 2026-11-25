import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

// A module with an Android target, through Google's KMP-library plugin (ADR-017). The plugin
// creates the `android` target when it is applied; the module's `kotlin { android { ... } }` block
// adds its own namespace and test setup.
//
// com.android.lint adds Android lint to the KMP-library target (the KMP plugin alone creates no lint
// task for the main code). Only one check runs: NewApi, as an error, so a call to a platform API
// newer than minSdk fails the build (`./gradlew lintAndroidMain`, run by the Android CI job). It
// caught ConcurrentHashMap.newKeySet() (API 24) under minSdk 23 (TES-346). Every other lint check
// stays off: this is an API-level gate, not a style review.
plugins {
    id("tessera.kotlin-multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.android.lint")
}

kotlin {
    extensions.configure<KotlinMultiplatformAndroidLibraryTarget> {
        // compileSdk tracks the latest stable API (37, Android 17 — ADR-017); minSdk 23 per ADR-018.
        compileSdk = 37
        minSdk = 23
        lint {
            checkOnly += "NewApi"
            error += "NewApi"
            abortOnError = true
            checkTestSources = false
            ignoreTestSources = true
            checkDependencies = false
            checkReleaseBuilds = false
        }
    }
}

// com.android.lint hooks `lint` into `check`. The API-level gate runs in the Android CI job
// (`lintAndroidMain`), which has the Android SDK; the JVM job's `check` stays as it was.
tasks.named("check").configure {
    setDependsOn(
        dependsOn.filterNot { dep ->
            val name = when (dep) {
                is TaskProvider<*> -> dep.name
                is Task -> dep.name
                is String -> dep
                else -> null
            }
            name != null && name.startsWith("lint")
        },
    )
}
