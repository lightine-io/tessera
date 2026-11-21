plugins {
    // Keep in step with `kotlin` in gradle/libs.versions.toml.
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    application
}

dependencies {
    implementation("io.lightine.tessera:mrz-core")
    implementation("io.lightine.tessera:types")
    // Keep in step with `kotlinx-datetime` in gradle/libs.versions.toml.
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("GenerateManifestKt")
}
