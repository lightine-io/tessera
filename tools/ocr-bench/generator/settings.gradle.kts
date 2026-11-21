// The OCR benchmark's test-set generator (TES-149). A standalone build, not part of the Tessera
// build: nothing here ships. It uses the live mrz-core through a composite build, so the MRZ text
// comes from Tessera's own generator.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ocr-bench-generator"

includeBuild("../../..") {
    dependencySubstitution {
        substitute(module("io.lightine.tessera:mrz-core")).using(project(":mrz-core"))
        substitute(module("io.lightine.tessera:types")).using(project(":types"))
    }
}
