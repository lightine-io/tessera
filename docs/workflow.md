# Work Lifecycle and Roles

How work flows through Tessera, who is trusted to do what, and how the YouTrack board relates to GitHub. The *why* behind this model is [ADR-0022](decisions/0022-operating-model-and-roles.md); this document is the operational reference.

> **Current state (2026-06):** the project is solo — the author plus an AI assistant, both acting as Owners and self-merging. The roles and gates below are the model the project will use *as it grows*; what is enforced today versus what waits for a second person is in [What is wired today](#what-is-wired-today).

## Two worlds

- **YouTrack — the inner workspace.** Planning, discovery, discussion, in-progress documentation. Private to Owners and equipped contributors. Optional: the git repo is always self-sufficient without it.
- **GitHub — the public truth.** Code, CI, review, merge, releases. The shipped product everyone sees.

Planning happens inner; enforcement happens on GitHub. **The board *reflects* GitHub; GitHub *enforces*.**

## Roles

| Role | What it is |
|---|---|
| **Owner** | Decides everything — roadmap, Epics, Decisions, final say. *(Today: the author + the AI assistant.)* |
| **Maintainer / Tech Lead** | The review gate — reviews and merges others' work. |
| **Developer** | Does the work — files tasks/bugs, builds, proposes. |

These are *authority* roles. They are distinct from the *platform* contributor types in [`contributor-map.md`](contributor-map.md), which routes by operating system (core / Android / iOS / docs). A person has one authority role and one or more platform types.

### Who can do what

| Action | Developer | Maintainer | Owner |
|---|---|---|---|
| File a task / bug | ✓ | ✓ | ✓ |
| Build · open PR | ✓ | ✓ | ✓ |
| Review · merge | — | ✓ | ✓ |
| Propose a Decision / Epic | ✓ | ✓ | ✓ |
| **Approve** a Decision | — | — | ✓ |
| **Create** an Epic · set the roadmap | — | — | ✓ |
| Administer the project | — | — | ✓ |

### Role mapping on each platform

| Role | YouTrack | GitHub |
|---|---|---|
| **Owner** | Project Admin | Admin |
| **Maintainer** | Contributor · *Maintainers* group | Maintain (merge rights) |
| **Developer** | Contributor · *Developers* group | Write · branch-protected |

A person's YouTrack and GitHub roles are kept **consistent** (one level in both) but are **not mechanically synced** — they govern different things (who *plans* vs. who *ships*). They are granted together when a contributor is equipped, not auto-mirrored. The authority that matters is the GitHub merge; the YouTrack role shapes the board.

## The work lifecycle (the board)

A task moves through these stages:

| Stage | Means | Who moves it |
|---|---|---|
| **Backlog** | captured, not yet refined | anyone files it |
| **Develop** | being built on a branch (`<type>/TES-N`, e.g. `bugfix/TES-1`) | a Developer picks it up |
| **Review** | PR open; CI runs; reviewed against the principles | the Developer (by opening the PR) |
| **Test** | reviewed and good — now *assure it works* (any test scenario, not only device) | a Maintainer / Owner |
| **Staging** | the Definition of Done is met and the PR is **merged**; complete, awaiting the next release | set by the merge |
| **Done** | **released** in a published version (e.g. `v0.3.0`) | set by the release |

**The handoff:** a Developer drives a task **up to Review**; a Maintainer/Owner takes it **Review → Test → Staging → Done**. Most board moves can be driven by GitHub events (PR opened → Review, merged → Staging, released → Done), so the board mirrors reality on its own.

**Two gates plus a release** — every other move is just status that mirrors git:

1. **Definition of Ready** (Backlog → Develop) — front-loads *alignment*.
2. **Merge** (Test → Staging) — CI green + a reviewer's approval. The *trust + alignment* gate.
3. **Release** (Staging → Done) — an Owner cuts the published version.

The board is kept honest: a task **cannot reach Staging without a merged PR, nor Done without a release** — it can never show more progress than git and the release history back.

### Definition of Ready / Definition of Done

**Ready** (may enter *Develop*): scoped · clear acceptance criteria · approach agreed and aligned with the [principles](principles.md) · no blocking unknowns.

**Done** (the work is complete and mergeable): built · reviewed · tests pass · docs synced · `CHANGELOG` updated (non-trivial) · an ADR if it was a decision · acceptance criteria met · **merged**. Meeting it moves the task to **Staging**; the **Done** *stage* adds one thing — it has **shipped in a release**. The DoD means the same thing for everyone because it is anchored to these objective criteria — the checkable parts enforced by CI, the judgment part by review.

## Per-type flows

| Type | Flow | Notes |
|---|---|---|
| **Bug** | Backlog → Develop → Review → Test → Staging → Done | full flow; the fix is verified before merge, ships at the next release |
| **Task** | Backlog → Develop → Review → *(Test)* → Staging → Done | Test optional — docs / trivial changes skip it |
| **Decision** | Backlog → Develop → Done | no code → skips Review / Test / Staging; Developers *propose*, an **Owner approves** (that approval is its "review"); "Done" = *decided & recorded*; an ADR, if needed, spins off its own Task |
| **Epic** | Open → Develop → Done | a container; "Done" when all its child tasks are Done; created by Owners |

## Trust — what we rely on

A contributor's **local** checks carry **zero trust weight** — they can be disabled and reverted invisibly. We rely only on what re-runs **on our side**:

- **Deterministic CI** on the PR — required to merge, author-blind, cannot be bypassed.
- **A reviewer** (Maintainer/Owner) re-running review and the project's review agents *on our infrastructure*.

Our gates catch **broken**; only review catches **misaligned**. See [ADR-0022](decisions/0022-operating-model-and-roles.md) for the reasoning.

## What is wired today

The model above is fully *designed*; at solo scale the enforcement that needs a second person is intentionally **not yet switched on**:

- **Live now:** the capability-gated YouTrack activation rule; deterministic CI (JVM + Android build & test, the `CHANGELOG` gate) required on every PR; the public repo's self-sufficiency; CODEOWNERS (`* @askerasadov`).
- **Waits for a real teammate:** GitHub **required review + CODEOWNER enforcement** (today `required_reviews = 0`, which suits a solo owner who cannot approve their own PR); the YouTrack **Maintainers / Developers** groups; the **board-integrity guard** (Staging needs a merged PR, Done needs a release); encoding more principles as CI lints.

Onboarding a teammate is then a **configuration step, not a redesign**.

## Related documents

- [ADR-0022](decisions/0022-operating-model-and-roles.md) — the decision and the reasoning behind this model.
- [`CONTRIBUTING.md`](../CONTRIBUTING.md) — the contributor entry point and the GitHub PR mechanics.
- [`contributor-map.md`](contributor-map.md) — contributor types by platform / OS (the other axis).
- [`.claude/rules/youtrack.md`](../.claude/rules/youtrack.md) — the YouTrack capability-gated activation rule.
