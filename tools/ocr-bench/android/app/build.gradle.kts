plugins {
    // Keep in step with `agp` in gradle/libs.versions.toml. AGP compiles the Kotlin itself.
    id("com.android.application") version "9.4.1"
}

android {
    namespace = "io.lightine.tessera.ocrbench"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.lightine.tessera.ocrbench"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The same ML Kit artifact and version as mrz-camera (`mlkit-text-recognition` in
    // gradle/libs.versions.toml): the bundled Latin model.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // The ONNX baseline (OnnxBenchActivity): ONNX Runtime for Android, CPU provider.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
