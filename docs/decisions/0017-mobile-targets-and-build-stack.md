# ADR-017: Mobile target enablement and build stack (0.2.0)

**Status:** Accepted

---

## Context

`0.1.x` ships JVM-only: the core modules (`types`, `mrz-core`, `emrtd-core`, `telemetry`, `logging`) declare a single `jvm()` target. `0.2.0` (live camera reading) is the first release that needs the **mobile targets** the architecture has always committed to ([ADR-001](0001-kotlin-multiplatform.md), [ADR-002](0002-native-ui-per-platform.md)) — Android and iOS — because camera I/O lives in platform-specific modules (`mrz-camera-android`, `mrz-camera-ios`) that depend on the core compiling for those targets.

Two things must be settled about *how* the build produces those targets, and one is not what the pre-implementation notes assumed:

1. **How the Android target is added.** [`open-questions.md`](../open-questions.md) ("Android target configuration on core modules") recorded the plan as *"add `androidTarget()` to each core module's `kotlin { }` block."* On the current toolchain — Kotlin 2.3.21 with the Android Gradle Plugin (AGP) 9.x line — that is no longer valid: the Kotlin Multiplatform plugin's `androidTarget` block produces a configuration error against AGP 9.0+, and JetBrains directs multiplatform *library* projects to Google's purpose-built `com.android.kotlin.multiplatform.library` plugin instead. The recorded guidance predates this.

2. **The JDK story.** AGP 9.x requires JDK 17 to run. The repository already pins the Gradle daemon to JDK 17 via the committed `gradle/gradle-daemon-jvm.properties` (`toolchainVersion=17`), so this is met for every machine without per-developer setup; the Kotlin compile toolchain stays JDK 21 (`jvmToolchain(21)`).

The minimum supported Android/iOS *versions* (`minSdk`, iOS deployment target) are a separate, commitment-level decision recorded in [ADR-018](0018-platform-minimums-and-managed-raise.md).

## Decision

**Enable the Android and iOS targets on the five core modules**, with the following build stack for `0.2.0` and the `0.x` line.

- **Android target via Google's `com.android.kotlin.multiplatform.library` plugin** (AGP 9.x), *not* the Kotlin plugin's legacy `androidTarget()` block. This supersedes the `androidTarget()` guidance in `open-questions.md`.
- **iOS targets** `iosArm64` (device) and `iosSimulatorArm64` (Apple-Silicon simulator) on the core modules — **ARM-only Mac development**; the single existing `expect`/`actual` pair (Unicode normalization, [ADR-014](0014-unicode-normalization-strategy.md)) gains an iOS `actual`. *(Amended 2026-06-04: the `iosX64` Intel-simulator target was originally enabled here and dropped before the `0.2.0` tag — Intel Macs are effectively end-of-life for new development, and the target cost build/CI time plus a slice in every shipped XCFramework for no Apple-Silicon benefit. Re-adding it is one line per module should an Intel-Mac contributor ever appear.)*
- **JDK:** Gradle daemon stays pinned to **JDK 17** (`gradle-daemon-jvm.properties`); compile toolchain stays **JDK 21** (`jvmToolchain(21)`, foojay-provisioned). No per-machine JDK pin is required.
- **Build against the latest stable SDKs:** Android `compileSdk` at the latest stable API (**API 36 / Android 16**); iOS built against the latest iOS SDK (26, per Xcode 26). *(Amended 2026-06-05: an earlier revision set `compileSdk` to API 37 / Android 17 on the belief it had gone stable, but Android 17 / API 37 is still in **beta** — confirmed against `developer.android.com/about/versions/17` (\"Android 17 Beta\") and the SDK Platform release notes, which list API 36 / Android 16 as the current stable. Reverted to 36 to honor this ADR's own latest-**stable**-only posture; `compileSdk` bumps to 37 via the dependency-upgrade cadence once Android 17 ships stable. Tessera uses no API-37-specific surface, so the revert is behavior-neutral.)*
- **Dependency posture:** track **latest *stable*** releases only — never alpha/beta — because [ADR-007](0007-strict-backward-compat-from-0x.md) makes the public API a standing commitment from `0.x`.

## Consequences

**Positive:**
- The core modules compile for Android and iOS, unblocking the camera I/O modules and the whole `0.2.0` arc.
- The build sits on the supported, current path (AGP 9 + the KMP-library plugin) rather than a deprecated one that would error.

**Negative:**
- The `com.android.kotlin.multiplatform.library` plugin is newer and less battle-tested than the long-standing `androidTarget()` path; specific DSL details are confirmed against Google's current migration guidance at implementation time rather than from memory.
- Pinning the daemon to JDK 17 while compiling on JDK 21 is a two-JDK setup; foojay provisions both, but it is a (documented) subtlety for new contributors.

**Neutral:**
- The architecture's module list and dependency graph are unchanged; this decision is about *which targets compile* and *how*, not about new modules.
- `compileSdk` tracking the latest stable API means a routine bump each Android release — ordinary maintenance, covered by the dependency-upgrade cadence.

## Alternatives Considered

- **Classic `androidTarget()` (the recorded plan).** Rejected: it errors on Kotlin 2.3.21 + AGP 9; staying on AGP 8.x to keep it alive would pin us to an older Gradle/AGP line against our latest-stable posture.
- **Pinning `compileSdk` to a fixed older API.** Rejected: building against the latest stable API is standard practice and is required by Google Play's target-API policy for consumers that ship; tracking latest costs only a routine per-release bump.

## Related Decisions

- [ADR-001](0001-kotlin-multiplatform.md) — Kotlin Multiplatform; this enables the mobile targets it always implied.
- [ADR-002](0002-native-ui-per-platform.md) — native UI per platform (UI itself lands at `0.5.0`).
- [ADR-007](0007-strict-backward-compat-from-0x.md) — strict backward compatibility; source of the latest-stable posture.
- [ADR-014](0014-unicode-normalization-strategy.md) — the `expect`/`actual` pair that gains an iOS `actual`.
- [ADR-016](0016-maven-coordinates-and-first-publish.md) — Maven coordinates and lockstep versioning (Android + JVM distribution).
- [ADR-018](0018-platform-minimums-and-managed-raise.md) — platform minimums and the managed-raise policy.
- [ADR-019](0019-ios-distribution-via-spm.md) — iOS distribution via SPM.
- [ADR-020](0020-camera-reading-architecture.md) — camera reading architecture.

## Related Documents

- [`../scope.md`](../scope.md) — committed platform coverage and the `0.2.0` pre-release review block.
- [`../architecture.md`](../architecture.md) — module list, dependency graph, the `mrz-camera-{platform}` modules.
- [`../open-questions.md`](../open-questions.md) — "Android/iOS target configuration on core modules" (resolved here).
- [`../development-setup.md`](../development-setup.md) — local toolchain for building the mobile targets.
