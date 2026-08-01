plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    // ABI-dump tooling for the root build's registerAndroidAbiTasks (the android-only ABI guard, TES-35).
    // Declared HERE rather than on the root project's buildscript classpath: buildSrc's runtime classpath
    // is added to every build script's classpath, so these are available to build.gradle.kts — WITHOUT an
    // explicit root `buildscript {}` block that would expose the whole build-time classpath (Dokka etc.)
    // to the submitted dependency graph. dependency-review gates only shipped deps; keeping build tooling
    // off the submitted graph is why this lives in buildSrc (see .github/workflows/dependency-submission.yml).
    implementation("org.jetbrains.kotlinx:binary-compatibility-validator:0.18.1")
    implementation("org.ow2.asm:asm-tree:9.7")
    // Keep in sync with libs.versions.toml `kotlin`.
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")
}
