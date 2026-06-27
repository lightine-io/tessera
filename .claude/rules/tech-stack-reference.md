---
paths:
  - "**/build.gradle.kts"
  - "settings.gradle.kts"
  - "gradle/libs.versions.toml"
  - "gradle/wrapper/gradle-wrapper.properties"
  - ".github/workflows/**"
---

# Keep the tech-stack index current

**When you add — or remove — a technology, tool, or dependency, update [`docs/tech-stack-references.md`](https://lightine.youtrack.cloud/articles/TES-A-23) in the same change.** That file is the single exhaustive index of the stack; it is only trustworthy if it never drifts from reality. The paths above are where the stack actually changes: a new dependency or version-catalog entry, a new plugin, a Gradle/JDK bump, a new CI action.

## What a new row needs

Match the existing schema — `Type | Vendor | Docs | Source | Added | Docs-verified`:

- **Docs** — the vendor's *official* documentation link, **verified live at write time** (this is the same habit as [`consult-vendor-docs.md`](consult-vendor-docs.md); a "Docs-verified" date is a promise you actually checked the link).
- **Source** — where the version is pinned (e.g. `libs.versions.toml` key, the wrapper, a build file). **Do not put the version number in the index** — it lives in that source of truth only; duplicating it creates a second thing to keep in sync.
- **Added** — the release the tech entered the project.
- **Docs-verified** — today's date.

Removing a tech? Remove its row. Bumping a version? The index row usually does not change (the version is not stored there) — but re-check the **Docs** link if the bump is major, and refresh **Docs-verified**.

## Scope

Fires on dependency/build/CI changes. It is the recording half of the stack-hygiene loop; [`consult-vendor-docs.md`](consult-vendor-docs.md) is the reading half (consult the docs before choosing). Both may load together.

## Cross-references

- The index itself: [`docs/tech-stack-references.md`](https://lightine.youtrack.cloud/articles/TES-A-23).
- The reading habit: [`consult-vendor-docs.md`](consult-vendor-docs.md).
