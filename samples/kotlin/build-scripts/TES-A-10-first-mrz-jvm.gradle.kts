// Build-script blocks of the page TES-A-10 (https://lightine.youtrack.cloud/articles/TES-A-10).
// Kept as text for the samples check; never compiled or applied. Labels as on the page.

// region TES-A-10 block 1
rootProject.name = "first-mrz"
// endregion

// region TES-A-10 block 2
plugins {
    kotlin("jvm") version "2.4.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("io.lightine.tessera:tessera-bom:1.0.1"))
    implementation("io.lightine.tessera:tessera-mrz-core")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("MainKt")
}
// endregion
