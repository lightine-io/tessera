---
paths:
  - "**/build.gradle.kts"
  - "settings.gradle.kts"
  - "gradle/libs.versions.toml"
  - "gradle/wrapper/gradle-wrapper.properties"
  - "**/androidMain/**"
  - "**/iosMain/**"
  - "**/appleMain/**"
  - "mrz-camera-*/**"
---

# Consult vendor documentation

**When your work touches an outside vendor's technology, read that vendor's current official documentation.** "Vendor" means anything we did not write: the Apple and Android platforms, an external library, build tooling (Gradle, GitHub Actions), an SDK. The paths above are where that happens — platform-specific code, the dependency list, and build configuration.

**Default to consulting the docs — don't wait until you feel unsure.** Built-in knowledge of these technologies is often months or years out of date, and the moments it is most dangerous are exactly the moments it *feels* certain. So the trigger is not "am I unsure?" — it is "is a vendor involved?" If yes, check, even when you think you already know.

**Consult to analyze, not to obey.** The docs are a strong, current input to a decision — not gospel. Vendors' own documentation is sometimes outdated or simply wrong (Android's especially, though it usually gets corrected later). You read it to inform your analysis: to find the current recommended approach, catch what's *deprecated*, and surface a *better option* than the one you would have guessed — then apply your own judgment. If the docs contradict what you actually observe, or look stale, that is a reason to dig further (release notes, issue trackers, other vendor pages), not a license to fall back on a guess. The goal is the normal one — analyze properly, then work — with current vendor docs as a primary source feeding that analysis.

## What this looks like in practice

- **Read the live source, not memory.** Fetch the current docs (Apple Developer, Android / AndroidX, Gradle, GitHub Actions, JetBrains / Kotlin) before writing platform code, choosing or upgrading a library version, or using a vendor API you have not verified recently.
- **Your confidence is not evidence.** If you notice you are sure about a vendor's behavior without having checked it, that is the signal to check — not to proceed. (The project lost real session time once to a confident, unchecked guess about iOS capture behavior; it was never compared against current AVFoundation / Kotlin-Native docs until days in.)
- **If a few attempts fail, stop guessing and go read.** Two or three failed iterations on platform/vendor behavior means the answer is in the docs, not in another guess.

## Why this is scoped, not always-on

This rule only loads on vendor-touching work — platform code, dependency, and build files. Ordinary core logic that involves no outside vendor does not trigger it, so consulting docs stays a normal habit in the places it matters rather than constant noise everywhere. It is fine to skip a doc-check for something genuinely trivial and just verified; the rule guards against confident guessing on vendor behavior, not against reusing a stable API you confirmed an hour ago.

## Scope

Applies whenever the loaded paths are touched. This is an epistemic habit (verify vendor behavior against current primary sources), distinct from [`mobile-dev-workflow.md`](mobile-dev-workflow.md), which is the *operating method* for mobile work (CLI, text-not-image, testing layers). Both may load together; they do not conflict.

## Cross-references

- Advisory counterpart in [`CLAUDE.md`](../../CLAUDE.md): "Verification Before Acting" (dependency justification, primary-source alignment). This rule is the auto-firing structural form of that habit.
- Mobile operating method: [`mobile-dev-workflow.md`](mobile-dev-workflow.md) and [`docs/mobile/android.md`](https://lightine.youtrack.cloud/articles/TES-A-19).
