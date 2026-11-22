// The :samples module (TES-230): every Kotlin code block of the public tutorials and how-to pages,
// as code CI compiles. One file per page, named after the page's KB article id; each block sits
// between `// region TES-A-<n> block <k>` and `// endregion`, and any wrapper a fragment needs to
// compile stays outside the region. `python3 -I scripts/check-docs.py samples --live` (release
// gate) checks that every page block matches its region. Build-script blocks (`build.gradle.kts`)
// are kept as text in build-scripts/ and are not compiled.
//
// Never published: no tessera.publishing / tessera.kmp-publishing plugin, so it has no publication,
// is not in the BOM (which lists only modules with the publishing plugin), and is not touched by
// publishToMavenLocal. No tessera.public-api plugin, so no ABI dump or explicit-API mode.
// The SDK modules are project dependencies, so the samples compile against the current sources.
plugins {
    id("tessera.android-library")
    alias(libs.plugins.compose.compiler)
}

// Compose runs on the Android target only; the JVM target has no Compose runtime.
composeCompiler {
    targetKotlinPlatforms.set(setOf(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm))
}

kotlin {
    // Chip reading is experimental; the pages tell readers to opt in once in the build script.
    compilerOptions {
        optIn.add("io.lightine.tessera.types.ExperimentalTesseraChipApi")
    }

    // Kotlin pages cover the JVM and Android: the core how-tos compile on the JVM target
    // (`check`, job `build & test · JVM`); the Android pages compile on the Android target
    // (`compileAndroidMain`, job `build & test · Android`).
    jvm()

    android {
        namespace = "io.lightine.tessera.samples"
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":mrz-core"))
                implementation(project(":telemetry"))
                implementation(project(":mrz-ocr-core"))
                implementation(project(":emrtd-core"))
            }
        }
        jvmMain {
            dependencies {
                implementation(project(":emrtd-pcsc"))
            }
        }
        androidMain {
            dependencies {
                implementation(project(":mrz-camera"))
                implementation(project(":mrz-camera-ui"))
                implementation(project(":emrtd-nfc"))
                implementation(libs.androidx.camera.core)
                implementation(libs.androidx.camera.lifecycle)
                implementation(libs.androidx.camera.compose)
                implementation(libs.androidx.compose.runtime)
                implementation(libs.androidx.compose.ui)
                implementation(libs.androidx.compose.foundation)
                implementation(libs.androidx.compose.material3)
                implementation(libs.androidx.activity.compose)
                implementation(libs.kotlinx.coroutines.android)
            }
        }
    }
}
