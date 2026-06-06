# Android Development Environment

Entry point for Android work in this repo, written for a reader with **no prior context** — a future session, a new machine, a new contributor. It answers four things: what the tooling *is*, what it *gives you*, what it *isn't*, and *how to work with it*.

This is **general Android knowledge**, not Tessera's — the Tessera-specifics are fenced in one section near the end, so the rest is portable to any Android project. The sibling [`ios.md`](ios.md) mirrors this shape for the Apple toolchain.

It describes **capabilities and method, never one machine's state.** Where the SDK happens to live, which AVD exists — that is per-machine and lives elsewhere (a local note), not here. For exact install commands it points at [`development-setup.md`](../development-setup.md) rather than duplicating them.

---

## 1. The model — CLI, skills, Knowledge Base

Android development here is driven by **Google's Android CLI** (`android`), an agent-first command-line tool (stable 1.0, Google-maintained; `android update` to refresh). Three layers:

- **CLI = the driver.** One entry point: scaffold projects, manage the SDK and emulators, deploy and run apps, inspect UI as text, look up versions, and reach the docs.
- **Skills = how-to playbooks.** Open-standard `SKILL.md` instruction sets from [github.com/android/skills](https://github.com/android/skills) (Apache 2.0), installed per-agent (e.g. `~/.claude/skills/`). An agent uses one *during* a task that matches its pattern (e.g. "migrate to AGP 9").
- **Knowledge Base = the reference.** The current developer.android.com corpus, queried **on demand**: `android docs search "<question>"` → `android docs fetch kb://…`.

*CLI drives · skills show how · KB explains why.* The KB is the everyday tool; skills apply only when a task matches one.

---

## 2. What the tooling gives you

Capabilities the CLI provides on **any** machine once installed — not an inventory of a particular machine:

| Capability | Command |
|---|---|
| Search/read current Android docs (Knowledge Base) | `android docs search` → `fetch kb://…` |
| Install/list/remove SDK packages | `android sdk install\|list\|remove` |
| Create/start/stop emulators | `android emulator …` (pin an image with `avdmanager -k`) |
| Deploy + launch an app | `android run --apks … --activity …` |
| Read a running UI as a JSON tree (text, not a screenshot) | `android layout --pretty` |
| Scaffold a project from a template | `android create` |
| Latest stable/preview versions (AGP, Gradle, SDK, libraries) | `android studio version-lookup …` *(needs Android Studio running)* |
| Manage agent skills | `android skills add\|list\|find` |

Plus the **skills catalog** (~18, across build / camera / identity / Compose / navigation / performance / Play / testing / Wear / XR) and the **Knowledge Base** (thousands of indexed docs) — both fetched/installed via the CLI, not carried per machine.

---

## 3. What it isn't / what you don't need

- **The CLI does not auto-provision a full SDK.** It bundles its own command-line tools and **auto-detects an existing SDK** at the default location; on a machine without one, you install packages yourself with `android sdk install`.
- **`android emulator create` is opinionated** — it can ignore an image you installed and pull its own default. Pin exact images with `avdmanager create avd -k "system-images;…"`.
- **`google_apis` system images still carry Play components** (`com.android.vending`). For a true "no Google Play Services" environment, use an AOSP image.
- **`android studio …` verbs need Android Studio running** (signed in) — not part of the headless path.
- **No `logcat`/shell verb.** `adb` is the escape hatch for logs and device shell only. adb ships *inside* the SDK's `platform-tools`; how to verify the authoritative binary and put it on `PATH` is in [`development-setup.md`](../development-setup.md) (Environment).
- **Skills are task-shaped; most won't apply to a given project.** A "camera" skill that's really about *migration* is irrelevant to greenfield work — don't treat the catalog as dependencies.
- **`github.com/android/skills` takes no public contributions** (file issues; don't expect to PR).
- **Windows gaps:** the `emulator` command is currently disabled there; PowerShell download isn't supported.

---

## 4. Setting up on a fresh machine

The knowledge (skills, Knowledge Base) is **not machine state you back up** — it installs/fetches via the CLI anywhere. Exact commands: [`development-setup.md`](../development-setup.md) (Tier 1). The shape:

1. **Install the CLI**, then `android init` (installs the `android-cli` skill for your agents) and `android skills add` (the skills you want).
2. **Get an SDK — pick your path:**
   - **You already have one** (e.g. a prior Android Studio install): the CLI **auto-detects** it at the default location. Nothing to install.
   - **From scratch:** install the packages you need with `android sdk install` — `android sdk list` shows exact IDs.
3. **Create an emulator** with a pinned image (`avdmanager -k`), since `android emulator create` picks its own default.
4. **Point the build at the SDK** (`ANDROID_HOME`, or a gitignored `local.properties`) and **verify**: `android info` reports the SDK, the emulator boots, `adb devices` lists it.

---

## 5. How to work — the method

- **Use a CLI verb when one exists; `adb` is the escape hatch only** (logs/shell). Don't hand-build tool paths — the CLI knows where things are.
- **Guidance comes from the Knowledge Base, not assumptions:** `android docs search "<what you need>"`, then `fetch` the `kb://` result. This is where current best-practice for CameraX, Compose, NFC, testing, etc. comes from.
- **Inspect state as text, never as an image:** `android layout` for the UI tree, `adb logcat` for logs. `android screen capture` produces a *screenshot* and is off-limits here — see [`mobile-dev-workflow.md`](../../.claude/rules/mobile-dev-workflow.md).
- **Skills vs Knowledge Base:** default to querying the KB; reach for a skill only when a task genuinely matches its pattern.
- **Check versions authoritatively** with `android studio version-lookup` (needs Studio) rather than guessing what's "latest."

---

## Tessera-specific

Everything above is general. For this project specifically:

- Tessera is a **headless KMP library** — no Android UI until 0.5.0, so the UI-oriented skills/KB topics don't apply yet.
- `compileSdk` tracks the latest stable Android API; `minSdk` is 23 — see [ADR-017](../decisions/0017-mobile-targets-and-build-stack.md) / [ADR-018](../decisions/0018-platform-minimums-and-managed-raise.md).
- Android camera I/O lands in the `mrz-camera-android` module (0.2.0); greenfield CameraX + ML Kit, so guidance comes from the **Knowledge Base** (`android docs search "CameraX ML Kit"`), not the migration skill.
- Build config (the KMP-library plugin, `compileSdk`) lives in the module `build.gradle.kts`; the machine-local SDK pin lives in a gitignored `local.properties`.

---

## iOS

The Apple-toolchain counterpart is [`ios.md`](ios.md) — it mirrors sections 1–5 for Xcode, the Xcode MCP, and the Simulator, and fences the iOS Tessera-specifics (the `mrz-camera-ios` module, XCFramework/SPM distribution, the Kotlin/Native GC pitfall) the same way.

---

**Verified working: 2026-06-06.** The Android CLI / Skills / Knowledge-Base model and the working method here were confirmed against the project's verified Tier 1 path in [`development-setup.md`](../development-setup.md) and the 0.2.0 Android camera slices. Re-verify against current Android tooling on the 6-monthly cadence.

---

## Related documents
- [`development-setup.md`](../development-setup.md) — exact install commands and environment setup (the setup side of this doc), including adb verification and PATH.
- [`.claude/rules/mobile-dev-workflow.md`](../../.claude/rules/mobile-dev-workflow.md) — the enforced AI operating rule (the screenshot border, testing layers); points back here for the full model and method.
- [`contributor-setup.md`](../contributor-setup.md) — Git/SSH setup; the dual-path, universal structure this doc follows.
