// Every Tessera module: Kotlin Multiplatform, compiled with JDK 21. A module declares its own
// targets (jvm(), iosArm64(), ...), because the set differs per module.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvmToolchain(21)
}
