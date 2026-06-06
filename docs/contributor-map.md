# Contributor Map

This document is the **router** for contributing to Tessera: it names the kinds of contributor the project expects, what each can and cannot work on (the hard constraints, mostly about which operating system a task needs), and where each should start. If you are not sure whether a task is open to you on your machine, or which setup document to read first, start here. It is for anyone — a new contributor, or the maintainer re-establishing the project on a fresh machine.

This document is *living*; it grows as the project gains platforms and tooling. It sits one level above the setup guides: [`CONTRIBUTING.md`](../CONTRIBUTING.md) points at the rules, [`contributor-setup.md`](contributor-setup.md) covers one-time machine setup (Git identity, SSH signing), and [`development-setup.md`](development-setup.md) covers the build/dev toolchain. This map tells you *which* of those you need.

---

## The one constraint that gates everything: your operating system

Most of Tessera is target-agnostic Kotlin that builds on any desktop OS. The single hard gate is **iOS work requires macOS** — Xcode and the Apple toolchain run only on macOS, so iOS camera/platform tasks are simply not available on Linux or Windows. Everything else (the multiplatform core, Android) is open on macOS, Linux, and Windows.

This is a *prerequisite*, not a preference: it is stated up front so no one sets up a machine expecting to do iOS work on Linux and discovers the wall halfway through.

---

## Contributor types — what each can work on, and where to start

| Type | Works on | OS constraint | Start here |
|---|---|---|---|
| **Multiplatform-core developer** | The pure parsing / validation / generation core, error taxonomy, lookup tables, telemetry, logging | Any OS (macOS / Linux / Windows) | [`contributor-setup.md`](contributor-setup.md) → [`development-setup.md`](development-setup.md) (JVM section) |
| **Android developer** | The Android camera surface and Android-specific source | Any OS that runs the Android SDK (macOS / Linux / Windows) | [`development-setup.md`](development-setup.md) → [`mobile/android.md`](mobile/android.md) |
| **iOS developer** | The iOS camera surface and iOS-specific source | **macOS only** (Xcode is macOS-only) | [`development-setup.md`](development-setup.md) → [`mobile/ios.md`](mobile/ios.md) |
| **Docs-only contributor** | Documentation, ADRs, conventions — no code | Any OS; only Git is required | [`CONTRIBUTING.md`](../CONTRIBUTING.md) → [`conventions.md`](conventions.md) |
| **Maintainer on a new machine** | Anything (re-establishing the full environment) | Per the task above; iOS needs macOS | [`contributor-setup.md`](contributor-setup.md) → [`development-setup.md`](development-setup.md), then platform guides as needed |
| **Future web / desktop / backend / AR developer** | Not yet real — these targets do not exist today | TBD when the target is added | When such a target lands it gets its own setup path; the target-agnostic core already supports the pattern |

The "future" row is deliberate: it records that the project expects new platform targets over its life, so this map is the standing place to add them — not a one-time mobile artifact. Adding a platform means adding a row here, a setup path, and a how-to guide that follows the guide-style convention (below).

---

## What a contributor needs before any of the above

Universal prerequisites (Git version, JDK, a GitHub account, signing setup) are not repeated here — they live in [`contributor-setup.md`](contributor-setup.md) under "What you need before starting." This map only routes; the setup docs hold the steps.

---

## The guide-style convention (how every how-to here is written)

Every how-to / setup guide in this project — the ones this map points at, and any future platform guide — follows a single shape so a contributor can trust it and get running without guessing. The convention is defined in [`conventions.md`](conventions.md) under "What Every How-To / Setup Guide Must Include": **prerequisites first** (they double as the capability gate), **one verified happy path** (the route the project actually confirmed, not a copied internet guide), **targeted troubleshooting** (only the real gotchas we hit), and a **"Verified working: [date]" stamp**. An AI assistant editing any guide auto-loads this convention via [`.claude/rules/guide-style.md`](../.claude/rules/guide-style.md).

---

## Related documents

- [`CONTRIBUTING.md`](../CONTRIBUTING.md) — the contributor entry point and the rules
- [`contributor-setup.md`](contributor-setup.md) — one-time machine setup (clone, Git identity, SSH signing)
- [`development-setup.md`](development-setup.md) — the build/dev toolchain (JDK, Android SDK, Xcode, agent tooling)
- [`mobile/android.md`](mobile/android.md) — the Android development model and method
- [`conventions.md`](conventions.md) — documentation, naming, and code conventions (including the guide-style convention)
