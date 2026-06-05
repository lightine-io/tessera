# Tech-Stack References

This document is the single, exhaustive index of **every technology, tool, and dependency** the project uses — what each one is, who makes it, and where its **official documentation** lives. It exists so that any decision about a tool starts from the vendor's current documentation rather than memory (the habit enforced by [`.claude/rules/consult-vendor-docs.md`](../.claude/rules/consult-vendor-docs.md)), and so a new contributor can see the whole stack at a glance.

This document is *living*. It is maintained by one rule: **whenever a technology or dependency is added, a row is added here** (enforced by [`.claude/rules/tech-stack-reference.md`](../.claude/rules/tech-stack-reference.md)).

**What is and isn't here:**
- **Versions are *not* duplicated here** — they live in their single source of truth (`gradle/libs.versions.toml`, the Gradle wrapper, the build files, `gradle/gradle-daemon-jvm.properties`, `.github/workflows/check.yml`). The **Source** column points at that location. Duplicating versions would create a second thing to keep in sync; this index deliberately does not.
- The **Docs-verified** column is a trust stamp: the official documentation link was confirmed live and correct on that date. **All links below were verified 2026-06-05.** They are re-verified on the project's six-monthly dependency-and-docs cadence (next: 2026-10-01).
- Local/agent **dev tooling** (the Android CLI, the Xcode MCP) is listed at the end but documented for setup in [`development-setup.md`](development-setup.md), not duplicated here.

---

## Language & multiplatform

| Type | Vendor | Docs | Source | Added | Docs-verified |
|---|---|---|---|---|---|
| Language | JetBrains | [kotlinlang.org/docs](https://kotlinlang.org/docs/home.html) | `libs.versions.toml` `kotlin` | 0.1.0 | 2026-06-05 |
| Kotlin Multiplatform plugin (`org.jetbrains.kotlin.multiplatform`) | JetBrains | [kotlinlang.org/docs/multiplatform-intro](https://kotlinlang.org/docs/multiplatform-intro.html) | `libs.versions.toml` `kotlin-multiplatform` | 0.1.0 | 2026-06-05 |
| Kotlin/Native (iOS compilation) | JetBrains | [kotlinlang.org/docs/native-overview](https://kotlinlang.org/docs/native-overview.html) | bundled with Kotlin (`kotlin` version) | 0.2.0 | 2026-06-05 |

## Build & toolchain

| Type | Vendor | Docs | Source | Added | Docs-verified |
|---|---|---|---|---|---|
| Build tool — Gradle | Gradle, Inc. | [docs.gradle.org](https://docs.gradle.org/current/userguide/userguide.html) | `gradle/wrapper/gradle-wrapper.properties` | 0.1.0 | 2026-06-05 |
| JDK — compile toolchain (21) | Eclipse Adoptium (Temurin, recommended) | [adoptium.net/temurin](https://adoptium.net/temurin/) | `jvmToolchain(21)` in `*/build.gradle.kts` | 0.1.0 | 2026-06-05 |
| JDK — Gradle daemon (17) | Eclipse Adoptium (Temurin) | [adoptium.net/temurin](https://adoptium.net/temurin/) | `gradle/gradle-daemon-jvm.properties` | 0.2.0 | 2026-06-05 |
| JDK auto-provisioning — foojay-resolver-convention | Gradle / foojay | [github.com/gradle/foojay-toolchains](https://github.com/gradle/foojay-toolchains) | `settings.gradle.kts` (plugins) | 0.1.0 | 2026-06-05 |
| Android Gradle Plugin (`com.android.kotlin.multiplatform.library`) | Google | [developer.android.com/kotlin/multiplatform](https://developer.android.com/kotlin/multiplatform) | `libs.versions.toml` `agp` | 0.2.0 | 2026-06-05 |

## Shared libraries

| Type | Vendor | Docs | Source | Added | Docs-verified |
|---|---|---|---|---|---|
| Coroutines — kotlinx-coroutines (core / android / test) | JetBrains | [github.com/Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | `libs.versions.toml` `kotlinx-coroutines` | 0.2.0 | 2026-06-05 |
| Date/time — kotlinx-datetime | JetBrains | [github.com/Kotlin/kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime) | `libs.versions.toml` `kotlinx-datetime` | 0.1.0 | 2026-06-05 |

## Android platform & camera stack

| Type | Vendor | Docs | Source | Added | Docs-verified |
|---|---|---|---|---|---|
| Android SDK platform (`compileSdk` / `minSdk`) | Google | [developer.android.com/tools/releases/platforms](https://developer.android.com/tools/releases/platforms) | `compileSdk` / `minSdk` in `*/build.gradle.kts` ([ADR-018](decisions/0018-platform-minimums-and-managed-raise.md)) | 0.2.0 | 2026-06-05 |
| CameraX (camera-core / camera-lifecycle / camera-camera2) | Google (AndroidX) | [developer.android.com/media/camera/camerax](https://developer.android.com/media/camera/camerax) | `libs.versions.toml` `camerax` | 0.2.0 | 2026-06-05 |
| ML Kit Text Recognition v2 (bundled Latin model) | Google | [developers.google.com/ml-kit/vision/text-recognition/v2](https://developers.google.com/ml-kit/vision/text-recognition/v2) | `libs.versions.toml` `mlkit-text-recognition` | 0.2.0 | 2026-06-05 |

## iOS platform stack

| Type | Vendor | Docs | Source | Added | Docs-verified |
|---|---|---|---|---|---|
| Xcode + iOS SDK (deployment target 18) | Apple | [developer.apple.com/xcode](https://developer.apple.com/xcode/) | toolchain ([ADR-018](decisions/0018-platform-minimums-and-managed-raise.md)); see [development-setup.md](development-setup.md) | 0.2.0 | 2026-06-05 |
| AVFoundation (camera capture) | Apple | [developer.apple.com/documentation/avfoundation](https://developer.apple.com/documentation/avfoundation) | Kotlin/Native cinterop (`iosMain`) | 0.2.0 | 2026-06-05 |
| Vision (OCR) | Apple | [developer.apple.com/documentation/vision](https://developer.apple.com/documentation/vision) | Kotlin/Native cinterop (`iosMain`) | 0.2.0 | 2026-06-05 |
| Other Apple frameworks — CoreMedia, CoreVideo, CoreFoundation, Foundation, darwin | Apple | [developer.apple.com/documentation](https://developer.apple.com/documentation) | Kotlin/Native platform libs (no version; bound to the iOS SDK) | 0.2.0 | 2026-06-05 |

## Testing

| Type | Vendor | Docs | Source | Added | Docs-verified |
|---|---|---|---|---|---|
| `kotlin.test` (assertions) | JetBrains | [kotlinlang.org/api/core/kotlin-test](https://kotlinlang.org/api/core/kotlin-test/) | `kotlin("test")` in test source sets | 0.1.0 | 2026-06-05 |
| Kotest — property-based testing (`kotest-property`) | Kotest | [kotest.io/docs/proptest](https://kotest.io/docs/proptest/property-based-testing.html) | `libs.versions.toml` `kotest` | 0.1.0 | 2026-06-05 |
| kotlinx-coroutines-test | JetBrains | [github.com/Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | `libs.versions.toml` `kotlinx-coroutines` | 0.2.0 | 2026-06-05 |

## Code quality & docs generation

| Type | Vendor | Docs | Source | Added | Docs-verified |
|---|---|---|---|---|---|
| Formatting — Spotless | DiffPlug | [github.com/diffplug/spotless](https://github.com/diffplug/spotless) | `libs.versions.toml` `spotless` | 0.1.0 | 2026-06-05 |
| Kotlin lint/format — ktlint (run via Spotless) | ktlint project | [ktlint.github.io/ktlint](https://ktlint.github.io/ktlint/) | `libs.versions.toml` `ktlint` | 0.1.0 | 2026-06-05 |
| API docs — Dokka | JetBrains | [kotlinlang.org/docs/dokka-introduction](https://kotlinlang.org/docs/dokka-introduction.html) | `libs.versions.toml` `dokka` | 0.1.0 | 2026-06-05 |

## Publishing & distribution

| Type | Vendor | Docs | Source | Added | Docs-verified |
|---|---|---|---|---|---|
| Publishing plugin — Gradle Maven Publish Plugin | vanniktech (Niklas Baudy) | [vanniktech.github.io/gradle-maven-publish-plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/) | `libs.versions.toml` `maven-publish` | 0.1.1 | 2026-06-05 |
| JVM/Android distribution — Maven Central (Sonatype Central Portal) | Sonatype | [central.sonatype.org](https://central.sonatype.org/) | [ADR-016](decisions/0016-maven-coordinates-and-first-publish.md); [publishing-setup.md](publishing-setup.md) | 0.1.1 | 2026-06-05 |
| iOS distribution — Swift Package Manager (XCFramework) | Apple / Swift.org | [docs.swift.org/swiftpm](https://docs.swift.org/swiftpm/documentation/packagemanagerdocs/) | [ADR-019](decisions/0019-ios-distribution-via-spm.md) | 0.2.0 | 2026-06-05 |

## CI (GitHub Actions)

| Type | Vendor | Docs | Source | Added | Docs-verified |
|---|---|---|---|---|---|
| `actions/checkout` | GitHub | [github.com/actions/checkout](https://github.com/actions/checkout) | `.github/workflows/check.yml` | 0.1.0 | 2026-06-05 |
| `actions/setup-java` | GitHub | [github.com/actions/setup-java](https://github.com/actions/setup-java) | `.github/workflows/check.yml` | 0.1.0 | 2026-06-05 |
| `actions/cache` | GitHub | [github.com/actions/cache](https://github.com/actions/cache) | `.github/workflows/check.yml` | 0.2.0 | 2026-06-05 |
| `gradle/actions/setup-gradle` | Gradle | [github.com/gradle/actions](https://github.com/gradle/actions) | `.github/workflows/check.yml` | 0.1.0 | 2026-06-05 |
| `gradle/actions/dependency-submission` | Gradle | [github.com/gradle/actions — dependency-submission](https://github.com/gradle/actions/blob/main/docs/dependency-submission.md) | `.github/workflows/dependency-submission.yml` | 0.2.0 | 2026-06-05 |
| `actions/dependency-review-action` | GitHub | [github.com/actions/dependency-review-action](https://github.com/actions/dependency-review-action) | `.github/workflows/dependency-review.yml` | 0.2.0 | 2026-06-05 |
| GitHub-hosted runners (`ubuntu-latest`, `macos-latest`) | GitHub | [docs.github.com — GitHub-hosted runners](https://docs.github.com/en/actions/using-github-hosted-runners/using-github-hosted-runners/about-github-hosted-runners) | `.github/workflows/check.yml` | 0.1.0 | 2026-06-05 |

## Repositories (dependency sources)

| Type | Vendor | Docs | Source | Added | Docs-verified |
|---|---|---|---|---|---|
| Maven Central | Sonatype | [central.sonatype.org](https://central.sonatype.org/) | `settings.gradle.kts` | 0.1.0 | 2026-06-05 |
| Google Maven (AGP / androidx / ML Kit — content-filtered) | Google | [developer.android.com/studio/build/dependencies — Google's Maven repo](https://developer.android.com/studio/build/dependencies) | `settings.gradle.kts` | 0.2.0 | 2026-06-05 |
| Gradle Plugin Portal | Gradle | [plugins.gradle.org](https://plugins.gradle.org/) | `settings.gradle.kts` (pluginManagement) | 0.1.0 | 2026-06-05 |

## Local / agent dev tooling

These drive development on a contributor machine; they are not build dependencies. Setup lives in [`development-setup.md`](development-setup.md) — referenced here for completeness, not duplicated.

| Type | Vendor | Docs | Source | Added | Docs-verified |
|---|---|---|---|---|---|
| Android CLI (SDK / emulator / adb orchestration) | Google | [development-setup.md](development-setup.md) (Tier 1) | local toolchain | 0.2.0 | 2026-06-05 |
| Xcode MCP (`mcpbridge`) — agent-driven iOS build/test | (MCP server) | [development-setup.md](development-setup.md) (Tier 2) | local toolchain | 0.2.0 | 2026-06-05 |

---

## Related documents

- [`development-setup.md`](development-setup.md) — how to install the toolchain (JDK, Android SDK, Xcode, agent tooling), tiered by what you work on.
- [`contributor-map.md`](contributor-map.md) — which contributor can use which part of this stack (the OS constraints).
- [`decisions/0017-mobile-targets-and-build-stack.md`](decisions/0017-mobile-targets-and-build-stack.md) — the mobile target + build-stack decision.
- [`.claude/rules/consult-vendor-docs.md`](../.claude/rules/consult-vendor-docs.md) — the habit this index supports: read the vendor's current docs before deciding.
