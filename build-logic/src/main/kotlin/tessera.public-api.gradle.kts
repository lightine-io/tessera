// A module with a public API: explicit API mode, and ABI tracking. ABI tracking enforces the
// ADR-007 no-public-API-break promise: it snapshots the public API under the module's api/ folder and
// fails `check` on an accidental break. Built-in Kotlin Gradle feature (no extra dependency, bundled
// with Kotlin so it never blocks a Kotlin bump). Refresh after an intended change:
// `./gradlew updateKotlinAbi`. A module adds its own filters with `abiValidation.filters { ... }`.
//
// The empty placeholder module (logging) does not apply this yet: explicit API mode and ABI
// tracking arrive with a module's first real source.
plugins {
    id("tessera.kotlin-multiplatform")
}

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
}
