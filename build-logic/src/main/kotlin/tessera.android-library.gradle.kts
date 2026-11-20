import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

// A module with an Android target, through Google's KMP-library plugin (ADR-017). The plugin
// creates the `android` target when it is applied; the module's `kotlin { android { ... } }` block
// adds its own namespace and test setup.
plugins {
    id("tessera.kotlin-multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    extensions.configure<KotlinMultiplatformAndroidLibraryTarget> {
        // compileSdk tracks the latest stable API (37, Android 17 — ADR-017); minSdk 23 per ADR-018.
        compileSdk = 37
        minSdk = 23
    }
}
