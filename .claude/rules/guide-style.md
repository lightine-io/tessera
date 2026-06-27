---
paths:
  - "**/*-setup.md"
  - "CONTRIBUTING.md"
---

# Guide-style convention

**This rule loads because you are editing a how-to or setup guide** — a document whose job is to get a contributor *running* (machine setup, a platform toolchain, a development workflow, the contributor router). These are not feature docs; they describe a procedure, and a stale or wrong procedure wastes a contributor's time silently. Write every such guide to one shape so it can be trusted.

The shape is defined once in [`docs/conventions.md`](https://lightine.youtrack.cloud/articles/TES-A-20) under "What Every How-To / Setup Guide Must Include". When editing a guide, hold to it:

1. **Prerequisites first** — what must already be true before the steps work, at the top, with a reference for where to get each tool. Prerequisites *are* the capability gate ("iOS work requires macOS" is a prerequisite, not a branch discovered halfway down).
2. **One verified happy path** — the single route the project has actually confirmed. Not a copied internet guide, not every theoretical variation — one path known to run.
3. **Targeted troubleshooting** — only the real gotchas this project actually hit, not an exhaustive failure tree. Full branching rots; a short list stays trustworthy.
4. **A "Verified working: [date]" stamp** — when this path was last confirmed against reality.

## Verify at write time

The "verified" stamp is a promise, so earn it: when a guide covers vendor tooling (Apple, Android, Gradle, GitHub), confirm the steps against the vendor's *current* docs before stamping a fresh date — do not copy a stale stamp forward unread. This is the same habit as [`consult-vendor-docs.md`](consult-vendor-docs.md), applied at the moment you document a procedure.

## Who the guide serves

Before writing or revising a guide, check who it is for and what they can do, in [`docs/contributor-map.md`](https://lightine.youtrack.cloud/articles/TES-A-6) — the router of contributor types and their OS constraints. A guide that assumes capabilities a reader does not have (e.g. iOS steps for a Linux contributor) fails them silently; the map is where that gating lives.

## Scope

Applies to the loaded paths — the mobile development guides, any `*-setup.md`, `CONTRIBUTING.md`, and the contributor map — and to any future how-to/setup guide (add its path here when it is created). The convention itself is permanent and platform-independent; this rule is its auto-loading enforcement.

## Cross-references

- The convention's canonical text: [`docs/conventions.md`](https://lightine.youtrack.cloud/articles/TES-A-20) → "What Every How-To / Setup Guide Must Include".
- The contributor router: [`docs/contributor-map.md`](https://lightine.youtrack.cloud/articles/TES-A-6).
- Vendor-doc verification habit: [`consult-vendor-docs.md`](consult-vendor-docs.md).
