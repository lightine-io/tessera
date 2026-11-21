import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

// Every Tessera module: Kotlin Multiplatform, compiled with JDK 21, published as Java 17
// bytecode. A module declares its own targets (jvm(), iosArm64(), ...), because the set differs
// per module.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvmToolchain(21)

    // The published JVM and Android classes are Java 17 bytecode, so an app that builds with
    // JDK 17 can use the SDK; the toolchain stays 21. On the JVM target the compiler also sees
    // only the Java 17 library (-Xjdk-release), so a call to a newer Java API is a compile error
    // here instead of a crash in an app. The Android target compiles against android.jar, which
    // already limits it. `PublishedBytecodeVersionTest` and the Maven smoke sample guard this.
    targets.configureEach {
        val isJvm = platformType == KotlinPlatformType.jvm
        val isAndroid = platformType == KotlinPlatformType.androidJvm
        if (isJvm || isAndroid) {
            compilations.configureEach {
                compileTaskProvider.configure {
                    (compilerOptions as? KotlinJvmCompilerOptions)?.apply {
                        jvmTarget.set(JvmTarget.JVM_17)
                        if (isJvm) freeCompilerArgs.add("-Xjdk-release=17")
                    }
                }
            }
        }
    }
}
