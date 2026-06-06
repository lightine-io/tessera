# iOS Development Environment

Entry point for iOS work in this repo, written for a reader with **no prior context** — a future session, a new machine, a new contributor. It answers four things: what the tooling *is*, what it *gives you*, what it *isn't*, and *how to work with it*. It is the sibling of [`android.md`](android.md) and follows the same shape.

**Prerequisite (the capability gate): iOS work requires macOS.** Xcode and the Apple toolchain run only on macOS, so the iOS camera/platform targets are simply not available on Linux or Windows — see [`contributor-map.md`](../contributor-map.md). This is stated up front so no one sets up expecting iOS work elsewhere and hits the wall halfway down.

This is mostly **general Apple-toolchain + Kotlin/Native knowledge**, not Tessera's — the Tessera-specifics are fenced in one section near the end. It describes **capabilities and method, never one machine's state.** Which Xcode build, which Simulator runtime, which paired device exists — that is per-machine and lives elsewhere (a local note), not here. For exact install commands it points at [`development-setup.md`](../development-setup.md) (Tier 2) rather than duplicating them.

> **Note on the Android↔iOS asymmetry.** Android development here runs through one first-party agent CLI with a Skills catalog and a Knowledge Base (see [`android.md`](android.md)). **iOS has no equivalent.** Apple ships no agent-first CLI, no skills, no queryable KB. So the iOS model is a different set of parts, and reference comes from **Apple's Developer documentation** read on demand — not a bundled corpus. Don't come here looking for an iOS "skills catalog"; there isn't one.

---

## 1. The model — Xcode, the Xcode MCP, the command-line tools

iOS development here has four parts, with no single driver that owns all of them:

- **Xcode = the toolchain.** The Apple compilers, SDKs, the Simulator, and code signing. Installing the app is not enough — it must be *wired*: point the active developer directory at it (`xcode-select -s`) and accept the license. Xcode also hosts the MCP bridge below.
- **The Xcode MCP (`mcpbridge`) = the agent driver.** A STDIO bridge (Xcode 26.3+) that **auto-attaches to a running Xcode instance** and exposes build / test / run / diagnostics to the assistant. Invoked as `xcrun mcpbridge`; registered with `claude mcp add xcode -- xcrun mcpbridge`. It is how device builds and interactive app-driving are done — *not* needed for plain compilation (see §3).
- **The command-line tools (`xcrun …`) = the escape hatches.** `xcodebuild` (build/test without the IDE), `xcrun simctl` (Simulator control — boot, install, `privacy … grant camera`), `xcrun devicectl` (physical-device install / launch / **console**). These resolve through the active toolchain — don't hand-build tool paths.
- **The Gradle/Konan build (`./gradlew`) = the KMP targets.** Compiling Tessera's iOS targets and running the Simulator test suite is plain Gradle (Kotlin/Native via Konan); it needs none of the above MCP machinery.

*Xcode is the toolchain · the MCP drives it for the agent · `xcrun` is the raw fallback · `./gradlew` builds the KMP targets · Apple's Developer docs explain why.*

---

## 2. What the tooling gives you

Capabilities available on **any** macOS machine once the Tier 2 toolchain is installed — not an inventory of a particular machine:

| Capability | How |
|---|---|
| Compile the iOS KMP targets | `./gradlew :<module>:compileKotlinIosArm64` / `…IosSimulatorArm64` (Konan; no MCP) |
| Run the iOS/common test suite on the Simulator | `./gradlew iosSimulatorArm64Test` (boots a sim, runs the Konan test binary — incl. Apple Vision on a *supplied image*) |
| Assemble the `Tessera` XCFramework (the SPM artifact) | `:mrz-camera-ios:assembleTesseraDebugXCFramework` / `…ReleaseXCFramework`, then `:mrz-camera-ios:packTesseraXCFramework` (zips it) |
| Build / run / diagnose through Xcode | the Xcode MCP (`mcpbridge`), with Xcode running |
| Control the Simulator | `xcrun simctl` (`boot`, `install`, `launch`, `privacy … grant camera`) |
| Install / launch / read console on a physical device | `xcrun devicectl device process launch --console …` (captures the app's stdout as text) |
| Inspect the toolchain | `xcodebuild -version` · `xcode-select -p` · `xcrun simctl list` · `xcrun devicectl list devices` |

Reference comes from **[developer.apple.com](https://developer.apple.com/documentation/)** queried on demand — the iOS counterpart of the Android Knowledge Base — read at write time (see §5 and [`.claude/rules/consult-vendor-docs.md`](../../.claude/rules/consult-vendor-docs.md)).

---

## 3. What it isn't / what you don't need

- **The Simulator has no camera.** It can run Apple Vision on a *supplied still image*, but a **live lens needs a physical device.** This shapes the testing layers (§5).
- **The Xcode MCP is only for device runs and interactive app-driving.** Compiling the iOS targets and running `iosSimulatorArm64Test` is plain `./gradlew` — no `mcpbridge`. Reach for the MCP when you need to drive a real device or an app on the Simulator, not before.
- **`mcpbridge` auto-attaches to a *running* Xcode.** If Xcode isn't open, `claude mcp list` shows the `xcode` server as **failed to connect** — that is the bridge having nothing to attach to, **not** a broken setup. Open Xcode, then it connects. Also: a session loads MCP servers only at **startup**, so after `claude mcp add xcode …` you must **restart** the assistant in this directory before the Xcode tools appear.
- **No paid Apple Developer Program needed for device debugging.** A **free personal Apple ID** signs a development build — with free-team caveats (see Troubleshooting): the app must be **trusted on-device**, and the device must be **unlocked** for a `devicectl` launch.
- **No screenshots.** Screen state is read as text; `xcrun simctl io … screenshot` is in the blocked set (the hard border — see §5 and [`mobile-dev-workflow.md`](../../.claude/rules/mobile-dev-workflow.md)).
- **No agent CLI / skills / KB** (the asymmetry called out above). Don't look for them.

---

## 4. Setting up on a fresh machine

The exact commands live in [`development-setup.md`](../development-setup.md) (Tier 2). The shape:

1. **Install Xcode** (26.3+), then wire it: `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer` and `sudo xcodebuild -license accept`. *(Both need the machine password — a human runs the `sudo` steps.)*
2. **A Simulator runtime** (bundled with / added through Xcode) for `iosSimulatorArm64Test`.
3. **Enable the Xcode MCP** (Xcode 26.3+ Intelligence setting) and register it at **local** scope so it stays tied to this machine and is never committed: `claude mcp add xcode -- xcrun mcpbridge`; then **restart** the session.
4. **For device work:** add a free Apple ID in Xcode → Settings → Accounts, pick the team in Signing & Capabilities (writes `DEVELOPMENT_TEAM`); pre-flight the device (Developer Mode on, paired, tunnel connected via `xcrun devicectl list devices`).
5. **Verify:** `xcodebuild -version` reports your Xcode; `./gradlew :mrz-camera-ios:compileKotlinIosArm64` compiles the iOS targets; with Xcode running, the MCP tools are visible to the assistant.

---

## 5. How to work — the method

- **Drive build/run/diagnostics through the Xcode MCP; compile + Simulator-test through `./gradlew`; read on-device output through `xcrun devicectl … --console`** (the app's `print`/`fflush` stdout, captured as text). Don't reach for the IDE GUI.
- **Inspect state as text, never as an image:** test/Gradle output, Xcode MCP diagnostics, and the `devicectl --console` stream. `xcrun simctl io … screenshot` / `screencapture` are **blocked for the assistant** by a PreToolUse hook (no override) — a screenshot can blow past image-size limits and destroy the whole session's context. If one is ever genuinely needed, the human takes it. See [`mobile-dev-workflow.md`](../../.claude/rules/mobile-dev-workflow.md).
- **Raw-tool guardrails:** `xcodebuild` and `xcrun simctl` route through the MCP — a PreToolUse hook blocks the raw call, with a deliberate `# raw-ok` override for when the MCP genuinely can't do the job. `xcrun devicectl` (the physical-device console) is intentionally **not** guarded — it's a legitimate raw use, the iOS analogue of plain `adb`.
- **Guidance comes from Apple's Developer documentation, not assumptions.** Read the current [developer.apple.com](https://developer.apple.com/documentation/) pages for AVFoundation / Vision / Swift-interop behavior at the moment you write code against them — the same write-time verification habit the rest of the project holds.
- **Testing layers (no live camera on the Simulator):**
  - **Host (JVM)** — glue logic tested with injected frames + mock OCR; no device. (The contract and the `scan` engine live in `mrz-camera-core`.)
  - **Simulator** — real Apple Vision on a *still image*; no camera.
  - **Physical device** — the only place a live lens is validated end-to-end (drive it as text via `devicectl … --console`).

---

## Tessera-specific

Everything above is general. For this project specifically:

- **iOS camera I/O lives in `mrz-camera-ios`** (`0.2.0`): `VisionMrzTextRecognizer` (the Apple Vision OCR seam, `MrzTextRecognizer<CMSampleBufferRef>`) and `AVCaptureMrzScanner` (the owns-the-session scanner, the iOS analogue of Android's `CameraXMrzScanner`), both built on the platform-agnostic `mrz-camera-core` contract ([ADR-021](../decisions/0021-shared-mrz-camera-core-module.md)). They use **only Kotlin/Native platform libraries** (AVFoundation / Vision / CoreMedia / CoreVideo / CoreFoundation) — **no external dependencies**, unlike Android's CameraX + ML Kit.
- **Targets:** `iosArm64` (device) + `iosSimulatorArm64` (Apple-Silicon simulator) — ARM-only; the `iosX64` Intel-simulator target was dropped before the `0.2.0` tag ([ADR-017](../decisions/0017-mobile-targets-and-build-stack.md)). iOS deployment minimum is 18 ([ADR-018](../decisions/0018-platform-minimums-and-managed-raise.md)).
- **Distribution is SPM, not Maven.** `mrz-camera-ios` is the umbrella that assembles the `Tessera` XCFramework and packs it into the zip an SPM `binaryTarget(url:checksum:)` consumes ([ADR-019](../decisions/0019-ios-distribution-via-spm.md)); the `mrz-camera-*` modules are **not** on Maven Central. The publication itself (distribution repo, GitHub-release asset, finalized `Package.swift`) lands at the `0.2.0` release cut.
- **The Swift-facing surface is provisional through `0.x`.** The Kotlin contract locks at the tag under [ADR-007](../decisions/0007-strict-backward-compat-from-0x.md), but the Swift projection (e.g. `MrzCameraScanner.results` as a Kotlin `Flow`) does not — an idiomatic adapter is deferred (`Flow`-ergonomics entry in [`open-questions.md`](../open-questions.md)).
- **The one genuinely-new iOS hazard:** Cocoa stores delegates/observers/targets **weakly**, and Kotlin/Native reclaims bridged Objective-C objects by **GC, not ARC** — so any weakly-held delegate needs a **strong Kotlin-side reference**, or it is collected mid-session and capture silently stops. And Core Foundation / Graphics / Media types (`CMSampleBuffer`, `CGImage`, …) are not ARC-managed — they need explicit `CFRetain`/`CFRelease`. Full write-up in [`.claude/known-pitfalls.md`](../../.claude/known-pitfalls.md) ("Cocoa Delegates Held Weakly + Kotlin/Native GC").

---

**Verified working: 2026-06-06.** Toolchain selection (`xcode-select -p` → Xcode.app) and the Xcode MCP bridge (`xcrun mcpbridge`) were re-confirmed on this date; the build / Simulator-test / XCFramework-assembly and on-device `devicectl --console` paths were exercised end-to-end during the `0.2.0` iOS camera slices (see [`development-setup.md`](../development-setup.md) Tier 2 and the iOS entries in [`CHANGELOG.md`](../../CHANGELOG.md)). Re-verify against current Apple docs on the 6-monthly cadence.

---

## Related documents
- [`development-setup.md`](../development-setup.md) — exact install commands and environment (Tier 2 is the setup side of this doc), including the XCFramework build tasks.
- [`android.md`](android.md) — the sibling Android development environment; the shape this doc mirrors.
- [`.claude/rules/mobile-dev-workflow.md`](../../.claude/rules/mobile-dev-workflow.md) — the enforced AI operating rule (the screenshot border, raw-tool guardrails, testing layers); points back here for the full model and method.
- [`contributor-map.md`](../contributor-map.md) — the contributor router and the macOS capability gate.
- [ADR-017](../decisions/0017-mobile-targets-and-build-stack.md) · [ADR-018](../decisions/0018-platform-minimums-and-managed-raise.md) · [ADR-019](../decisions/0019-ios-distribution-via-spm.md) · [ADR-021](../decisions/0021-shared-mrz-camera-core-module.md) — targets, platform minimums, SPM distribution, the shared camera-core contract.
