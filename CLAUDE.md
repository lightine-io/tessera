# CLAUDE.md

This file orients AI assistants — particularly Claude Code — picking up this project. Read this first; load deeper material from `docs/` and `.claude/` as needed.

If you are a human contributor, [`README.md`](README.md) is the better starting point.

---

## Project at a Glance

Tessera is a vendor-neutral SDK for reading, validating, and generating identity document data — primarily MRZ from passports, ID cards, residence permits, and visas conforming to ICAO Doc 9303. NFC chip reading and other capabilities are planned for later releases.

**Current state:** In active `0.x` development, published to Maven Central (`io.lightine.tessera`). `v0.1.0` (2026-05-19) shipped the core MRZ parsing/validation/generation for all five ICAO Doc 9303 formats, the error taxonomy, lookup tables, transliteration profiles, and the pluggable telemetry interface; `v0.2.0` adds headless live-camera MRZ reading on Android (CameraX + ML Kit) and iOS (AVFoundation + Apple Vision) across the multiplatform module set. Roadmap through `1.0.0` in [`docs/scope.md`](docs/scope.md); release contents in [`CHANGELOG.md`](CHANGELOG.md).

---

## What to Do First

If you are starting a new session:

1. Look for the most recent `SESSION-HANDOFF-YYYY-MM-DD-HHMM-<slug>.md` in `.handoffs/`. The current convention is date + UTC time (4 digits, no separator) + kebab-case slug, so `ls -1 .handoffs/SESSION-HANDOFF-*.md | sort -r | head -1` returns the canonical latest within the new form. The `YYYY-MM-DD` prefix ensures date order across all forms; within any single date, all handoffs share the form current at that time, so within-date mixing is not expected. Older dates may use either legacy form — `SESSION-HANDOFF-YYYY-MM-DD-<slug>.md` (date + slug, no time) or `SESSION-HANDOFF-YYYY-MM-DD.md` (date only, no slug). Treat all forms the same when reading.
2. Sweep standing obligations: `grep -n '\- \[ \]' .plans/*.md` — unchecked action-plan items are open work **even if the latest handoff says nothing is in flight** (a handoff is one session's snapshot; plans are the ledger). A SessionStart hook also injects these automatically.
3. If no handoff exists, briefly skim [`docs/principles.md`](docs/principles.md) to refresh the foundational stance (10 min).
4. Check [`docs/open-questions.md`](docs/open-questions.md) for what is currently in flight.
5. Engage with the user's request.

Do not draft major changes without first understanding what is already in place. The documentation captures decisions that are easy to accidentally undo.

---

## Document Map — Where to Find Things

If you are looking for...

| You need... | Look at... |
|---|---|
| The foundational principles | [`docs/principles.md`](docs/principles.md) |
| What the SDK supports / does not support | [`docs/scope.md`](docs/scope.md) |
| Module structure and dependencies | [`docs/architecture.md`](docs/architecture.md) |
| How decisions get made and how docs are written | [`docs/conventions.md`](docs/conventions.md) |
| Versioning and release rules | [`docs/versioning.md`](docs/versioning.md) |
| Testing discipline | [`docs/testing.md`](docs/testing.md) |
| The risk profile of each reading method | [`docs/reading-risks.md`](docs/reading-risks.md) |
| Definitions of MRZ, BAC, PACE, etc. | [`docs/glossary.md`](docs/glossary.md) |
| What is currently deferred or in flight | [`docs/open-questions.md`](docs/open-questions.md) |
| Why a significant decision was made | [`docs/decisions/`](docs/decisions/) |
| A specific feature's design | [`docs/features/`](docs/features/) |
| Concrete working patterns | [`.claude/working-patterns.md`](.claude/working-patterns.md) |
| Failure modes to avoid | [`.claude/known-pitfalls.md`](.claude/known-pitfalls.md) |
| What goes in the public repo and what doesn't | [`.claude/gitignore-planning.md`](.claude/gitignore-planning.md) |
| Session handoff template | [`.claude/session-handoff-template.md`](.claude/session-handoff-template.md) |
| Git and GitHub workflow (branch naming, PR flow, gh CLI usage) | [`.claude/git-workflow.md`](.claude/git-workflow.md) |
| Local build/dev toolchain & mobile (CLI/MCP) workflow | [`docs/development-setup.md`](docs/development-setup.md) |
| The Android development environment (CLI, skills, Knowledge Base, how to work) | [`docs/mobile/android.md`](docs/mobile/android.md) |
| The iOS development environment (Xcode, the Xcode MCP, the Simulator, how to work) | [`docs/mobile/ios.md`](docs/mobile/ios.md) |

---

## The Principles That Matter Most

Full list in [`docs/principles.md`](docs/principles.md). The four that shape day-to-day work most directly:

- **Principle 1 — Reader, not oracle.** The SDK extracts data verbatim and reports observations. It does not make trust decisions. No `isValid` boolean, no auto-correction, no inference of intent.
- **Principle 4 — Honest about what we know.** Distinguish settled facts from current decisions from open questions from speculation. Do not pretend certainty you do not have.
- **Principle 5 — Transparency.** If the SDK extracts data internally, it exposes it externally. Raw values are exposed alongside computed values. Nothing is hidden.
- **Principle 11 — Internal packages first.** New features start as internal packages within existing modules. Promote to standalone module only when justified.

When tempted to do something convenient, check whether you are crossing into oracle territory. If yes, stop.

---

## Rules

The operational ruleset. Each rule is concrete and triggers on a specific situation.

### Session Discipline

- **At the end of a substantive working session**, write a session handoff file in `.handoffs/` named `SESSION-HANDOFF-YYYY-MM-DD-HHMM-<slug>.md`, where the time component is the current UTC time as four digits with no separator (e.g., `0930`, `2256`) and `<slug>` is a short kebab-case summary of what shipped (match the feature branch's slug when there is one — e.g., `validator`, `expiry-warnings`, `explicit-api`). Use the template in [`.claude/session-handoff-template.md`](.claude/session-handoff-template.md). A "substantive" session means: more than a small fix, decisions made that affect future work, or work stopped mid-task. Both the time and the slug are mandatory: the time makes `ls | sort -r` deterministic without depending on filesystem mtime (which gets clobbered by `git clone`, `rsync`, archive extraction, etc.); the slug makes the directory listing self-documenting at a glance.
- **At the start of a session**, look for the most recent handoff file and read it first.
- **Use `/clear` between distinct tasks** to reset context.
- **Use `#` to capture recurring instructions** so they persist across sessions.

### Forbidden Actions

- **Never commit credentials, secrets, API keys, tokens, or private content.**
- **Never name specific organizations** in any committed file (no employer organizations, no anchor users, no business partners). Standards bodies (ICAO, ISO/IEC) and authorities issuing specific laws may be cited for accuracy.
- **Never include real document data** in tests, fixtures, or examples. Synthetic data only, generated by the SDK's own generator. ICAO-published test vectors where applicable.
- **Never commit `SESSION-HANDOFF-*.md` files.** They are working notes; the `.gitignore` excludes the entire `.handoffs/` directory.
- **Never auto-correct or auto-infer trust decisions** in the SDK code. Reader, not oracle.
- **Never embed version numbers in feature documentation body text.** Use structural markers (`Available since X.Y.Z`) only.

### Required Discipline

- **Tests for every new public API.** No public API ships without a test that exercises documented behavior.
- **Tests alongside implementation, not after.** Write the test as you build the feature, not as a follow-up.
- **Tests for every new error type.** When adding a new error to the taxonomy, add a test that produces it.
- **When tests reveal an error condition that has no type yet, add it to [`docs/features/mrz-error-taxonomy.md`](docs/features/mrz-error-taxonomy.md) and write a test that produces it.** The error taxonomy grows through discovery.
- **Update `CHANGELOG.md` for every non-trivial PR.** Keep a Changelog format. Entries go under `[Unreleased]`, grouped Added / Changed / Deprecated / Removed / Fixed / Security. Trivial PRs (single-character typo fixes etc.) can skip with a one-line explanation in the PR description. Detail in [`.claude/git-workflow.md`](.claude/git-workflow.md).
- **Read the relevant ADR before changing established ground.**

### Folder and File Organization

Visible folders (`docs/`, `scripts/`, source modules) hold project deliverables; dot-prefix folders (`.claude/`, `.handoffs/`, `.recaps/`, `.conformance/`, `.spec/`) hold project infrastructure. Naming is purpose-driven: dated working notes use `<CATEGORY>-YYYY-MM-DD[-HHMM][-<slug>].md`, evergreen docs use lowercase-hyphen `.md`, root-level files use UPPERCASE. Full rule (with examples and scope) in [`.claude/rules/folder-organization.md`](.claude/rules/folder-organization.md) — auto-loaded when working with markdown files. Cross-reference for human contributors in [`docs/conventions.md`](docs/conventions.md).

### Dependency Upgrade Cadence

Six-monthly toolchain and dependency bumps to current stable. **Next scheduled check: 2026-10-01** (then every 6 months thereafter; ±2 weeks is fine). Full procedure — what to bump, how to verify compatibility, how to split into PRs — in the [`dependency-upgrade-cadence`](.claude/skills/dependency-upgrade-cadence/SKILL.md) skill. Cross-reference for human contributors in [`docs/conventions.md`](docs/conventions.md).

At each cadence checkpoint, also do a **CLAUDE.md health review** (5-minute pass piggy-backed on the dependency check): confirm the file is still under 200 lines, no rules that should have been path-scoped or made into skills have crept back in, no rules have gone stale relative to actual practice.

### Pre-Release Tech-Stack Review

Before starting work on each `0.x` (or later `x.0` / `x.y`) release, run an explicit tech-stack review — does the foundation still fit what we're about to build? Trigger is pre-start, not pre-tag. Full procedure (scope, output, what it isn't) in the [`pre-release-tech-stack-review`](.claude/skills/pre-release-tech-stack-review/SKILL.md) skill. Cross-reference for human contributors in [`docs/conventions.md`](docs/conventions.md).

### Git and GitHub Workflow

The project uses **GitHub Flow**: `main` is the trunk; feature branches off `main`; PR for every merge. Detail in [`.claude/git-workflow.md`](.claude/git-workflow.md).

- **Branch off `origin/main`** for every PR. Name `feature/...`, `fix/...`, `docs/...`, or `chore/...` per the contents.
- **Rename auto-generated worktree branches** before pushing. `claude/<random>` is meaningless to reviewers.
- **Private-content scan before every push.** Automated for Claude's `git push` calls via a PreToolUse hook in [`.claude/settings.json`](.claude/settings.json) calling [`scripts/private-content-scan.sh`](scripts/private-content-scan.sh); manual pushes run the script directly. Setup, false-positive allowlist, and resolution steps in [`.claude/git-workflow.md`](.claude/git-workflow.md) section 4.
- **Use the PR template** at `.github/pull_request_template.md`. Fill Documentation Impact, Tests, Open Questions, Changelog, and Verification sections.
- **`gh` CLI is set up and authed** in this environment. Push and create PRs directly from the session.
- **Delete merged branches locally** with `git branch -d <name>` after merge. Remote branches auto-delete via repo settings.

### Documentation Sync (When You Change X, Update Y)

- **When changing a public API in code**, update the relevant feature document in [`docs/features/`](docs/features/) so illustrative shapes match the actual API.
- **When making a significant decision**, draft a new ADR in [`docs/decisions/`](docs/decisions/) and add it to the index.
- **When implementation reveals a doc gap or contradiction**, surface it and update the doc — do not silently work around it.
- **When adding a new test category or testing commitment**, update [`docs/testing.md`](docs/testing.md).
- **When resolving an item in [`docs/open-questions.md`](docs/open-questions.md)**, mark it Resolved with a reference to where the resolution was made. Do not delete.
- **When deferring a new decision**, add an entry to [`docs/open-questions.md`](docs/open-questions.md).

### Verification Before Acting

- **Before drafting major changes**, confirm the change does not contradict an established decision.
- **Before introducing a new dependency**, confirm it is justified. Each adds a maintenance burden.
- **Before writing more than ~50 lines of new content without checking in**, pause and verify direction with the user. Short cycles produce better outcomes.
- **Before committing to a foundational decision**, verify alignment with primary sources — the actual committed docs (`scope.md`, ADRs, `open-questions.md`, feature docs) — not derived sources (recaps, summaries, prior interpretations) that can drift. Foundational decisions are anything ADR-007 backward-compatibility will lock at `0.1.0` (tech-stack choices, scope-defining wording, architectural commitments). The trigger is *cost of being wrong is high*, not *I want to feel thorough*. Full pattern in [`.claude/working-patterns.md`](.claude/working-patterns.md) under "Pre-commitment alignment check"; complements the "trust the doc system" stance by naming its exception.

### Resisting "Looks Ready"

A known failure mode: declaring work "ready" prematurely, only to find gaps under follow-up questioning.

- **Be skeptical of your own "ready" judgments.** Before declaring something complete, ask: "what would I check if I were skeptical of my own work?"
- **Bias toward acknowledging incompleteness.** A doc set is more often *almost* ready than fully ready. Surface what you are uncertain about rather than papering over it.
- **Treat the user's follow-up questions as signal, not friction.** When they ask "did we cover X?" they are often right that you did not.
- **A "no issues found" verification is suspicious.** If a check produces zero findings, double-check the check itself before trusting the result.

---

## Maintaining the Documentation System

When new content needs a home, use this guidance.

**AI-facing rules and workflows** (the placement test: *would removing this cause Claude to make mistakes?* — see Anthropic's [memory-files guidance](https://code.claude.com/docs/en/memory) for the full reasoning):

| New content is... | Goes in... |
|---|---|
| A rule Claude needs in every session (behavioral, foundational, security-critical) | This file (CLAUDE.md, Rules section) |
| A rule that applies when working with specific files or paths | A new file in [`.claude/rules/`](.claude/rules/) with `paths:` frontmatter |
| A workflow triggered by a specific event (release prep, dep bump, etc.) | A new skill directory in [`.claude/skills/`](.claude/skills/), invoked explicitly |
| A deterministic action that must happen every time, no exceptions | A hook in `.claude/settings.json` (lifecycle events; not advisory) |
| A specialized review or investigation task with isolated context (no main-context pollution) | A new subagent in [`.claude/agents/<name>.md`](.claude/agents/), invoked via the `Agent` tool with `subagent_type: "<name>"` |

**Project documentation and working-note content:**

| New content is... | Goes in... |
|---|---|
| A working pattern observed during implementation | [`.claude/working-patterns.md`](.claude/working-patterns.md) |
| A mistake to avoid | [`.claude/known-pitfalls.md`](.claude/known-pitfalls.md) |
| A deferred decision | [`docs/open-questions.md`](docs/open-questions.md) |
| A significant architectural or scope decision | A new ADR in [`docs/decisions/`](docs/decisions/) |
| A new domain term | [`docs/glossary.md`](docs/glossary.md) |
| A new API or capability | The relevant feature doc in [`docs/features/`](docs/features/) |
| A testing commitment | [`docs/testing.md`](docs/testing.md) |
| A risk profile for a new reading method | [`docs/reading-risks.md`](docs/reading-risks.md) |

Some rules and patterns serve **multiple audiences** — both AI assistants and human contributors. When that's the case, place the content in its primary home and cross-reference from secondary homes. The established precedent in this project: rules and skills that also affect human contributors get a short cross-reference subsection in [`docs/conventions.md`](docs/conventions.md). Before placing a new rule, ask: *who does this serve — future AI sessions, future human contributors, or both?* Place and cross-reference accordingly. Memories serve a narrower audience (one Claude on one machine) and are appropriate when the content is genuinely local; rules that apply to any contributor or any AI on the repo belong in committed files instead.

After every substantive session, ask: *did this session reveal anything that should be added to the documentation? Was anything in the docs wrong relative to what was built?* If yes, update before writing the handoff.

---

## Working Style

The user has been clear about how they want collaboration to work:

- **Honest engagement over reflexive agreement.** Push back with reasoning when warranted. Hold positions when confident.
- **Show reasoning, not just conclusions.** Trade-offs named explicitly. The pattern *Decision I'd make / Why I'm leaning this way / Where you might disagree* surfaces reasoning before it becomes a fait accompli.
- **Distinguish "your lean" from "your call."** When the user defers to you on something they could legitimately have an opinion on, check whether they have an underlying preference. Decisions about their project, identity, or business choices belong to them.
- **Acknowledge uncertainty honestly.** Say "I don't know" or "let me check" rather than fabricating certainty.
- **Treat the user as a peer.** Different powers, equal standing. Servile collaboration produces worse output than peer collaboration.

Detailed working patterns are in [`.claude/working-patterns.md`](.claude/working-patterns.md).
