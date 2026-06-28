---
paths:
  - "**/*.kt"
---

# Future-proofing — the duty to look

**This rule loads because you are building something** — code or documentation — and future-proofing is a duty here, not a vague aspiration. The duty is concrete:

> Use what we already hold — the source, the docs, the scope, the open questions — to spot opportunities (in code, docs, and process), surface them with a recommendation, and decide *together*: now / defer / park. **The only failure is not looking.**

## Why this is your duty specifically

You write the code, so you are the one positioned to see what could be reused, extracted, exposed, or turned into a standing convention. The maintainer cannot see opportunities in code he did not write. That asymmetry makes surfacing **not optional** — he should not have to ask you "did you notice X?"; you should be telling him. Silence is the failure mode.

## The three parts

1. **Surface while working — at the moment, not later.** When something you are writing looks reusable, exposable, or worth documenting for future scope (a later feature like NFC, another platform, another contributor), say so *then*, with a recommendation. Don't let it pass silently to be rediscovered later.
2. **Backstop at task end.** Before calling a task done, ask: *does this serve future scope or other contributors? Is there anything to extract, expose, document, or make a project-wide convention?* Run this check even when the task "feels" finished.
3. **Classify the scope, then act on it.** When making a decision, name whether it is *feature-local* or *project-wide*. If it is project-wide, record it as a convention **immediately** — so it is never re-explained later. (The standing lesson: a project-wide decision left unrecorded gets re-litigated; record it once, at the moment you recognize it.)

## Three valid outcomes — only silence is wrong

Once an opportunity is surfaced, any of **now / defer / park** is a good outcome:

- **Now** — do it while the context is hot.
- **Defer deliberately** — decide explicitly to wait (e.g. keep a throttle private until a real user reports slowness). A recorded, reasoned deferral *is* future-proofing.
- **Park** — log it in the project's issue tracker for later.

Choosing "defer" or "park" is never the failure. **Not spotting or not surfacing** is the only failure.

## This is not oracle-overreach

Do not confuse this with [Principle 1 — reader, not oracle](https://lightine.youtrack.cloud/articles/TES-A-5), which binds the **SDK's** behavior toward its users (never guess the user's intent, never make trust decisions for them). That constraint is about the SDK. This duty is about **us building the project**: we hold the full inputs — scope, ADRs, the whole codebase — so seeing "this helps NFC later" or "this belongs in `mrz-camera-core`" is just reading our *own* situation well, which we have every right and obligation to do.

## Scope

Loads on Kotlin source. The duty itself spans code, docs, and process; when working on process/tooling outside these paths (e.g. `.claude/`), carry the same habit even though this file did not auto-load. It is a meta-habit, so its real test is whether opportunities actually get surfaced — if they stop, escalate the enforcement (e.g. a task-end hook), don't just leave the rule unread.

## Cross-references

- The clearest concrete instance: [`working-patterns.md`](../working-patterns.md) → "Internal packages first" (spot reuse → extract a clean boundary) and "Present, don't decide" (surface with a recommendation, decide together).
- [`../../docs/conventions.md`](https://lightine.youtrack.cloud/articles/TES-A-20) → "Internal Packages First" and "How New Conventions Are Added".
- [`../../CLAUDE.md`](../../CLAUDE.md) → Principle 11 (internal packages first) and the "Working Style" stance on surfacing reasoning.
