plugins {
    id("tessera.public-api")
    id("tessera.kmp-publishing")
}

// Desktop Java transport for the platform-neutral chip-reading core. PC/SC and javax.smartcardio
// are supplied by the host JDK, so this module has no runtime dependency beyond emrtd-core.
mavenPublishing {
    coordinates(group.toString(), "tessera-emrtd-pcsc", version.toString())

    pom {
        name.set("tessera-emrtd-pcsc")
        description.set(
            "Experimental desktop Java transport for Tessera chip reading through PC/SC smart-card readers.",
        )
    }
}

kotlin {
    jvm {
        // java.smartcardio is a JDK module outside java.se; make it visible to the Kotlin compiler
        // while the shared convention still limits the available APIs to Java 17.
        compilerOptions.freeCompilerArgs.add("-Xadd-modules=java.smartcardio")
    }

    sourceSets {
        all {
            // This module implements the experimental chip API; its consumers still opt in.
            languageSettings.optIn("io.lightine.tessera.types.ExperimentalTesseraChipApi")
        }
        jvmMain {
            dependencies {
                api(project(":emrtd-core"))
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
