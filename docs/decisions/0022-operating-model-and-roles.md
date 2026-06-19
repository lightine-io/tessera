# Operating Model and Project Roles

## Status

Accepted (2026-06-19)

## Context

Tessera adopted a private YouTrack instance for planning and a Knowledge Base, gated by a capability rule ([`.claude/rules/youtrack.md`](../../.claude/rules/youtrack.md)). That raised a larger question the project had deferred: **how does work actually flow, and who is trusted to do what** — not only today (a solo author working with an AI assistant), but as the project grows to include a maintainer or outside contributors.

The deep review that resolved this (2026-06) had to answer three things at once:

- Where does *planning* live, and where does the *truth* of the project live?
- When a contributor — human or AI — whom we do not fully control does work, how do we *trust* it without slowing everyone down?
- How do we ensure their work is *aligned* with the project's principles, not merely correct?

## Decision

Adopt one operating model with four load-bearing commitments.

**1. Two worlds — YouTrack is the inner workspace; GitHub is the public truth.**
Planning, discovery, discussion, and in-progress documentation happen in YouTrack — the inner workspace of the owners and trusted contributors. The shipped product and its enforcement (code, CI, review, merge, releases) live on public GitHub. The committed git repository is always self-sufficient; YouTrack is an optional inner enhancement, never a dependency. (This is why the activation rule keeps YouTrack dormant for anyone unequipped: the public repo is missing nothing required.)

**2. Trust only what re-runs on our side.**
A contributor's *local* checks — a hook, an advisory agent — carry **zero trust weight**: they can be disabled and reverted with no trace. The only checks we trust are the ones that re-execute where we control them: deterministic CI on the PR (required to merge) and a maintainer/owner re-running review on our infrastructure. *Our gates catch broken; only review catches misaligned.*

**3. Alignment is front-loaded, then back-stopped.**
Misalignment with the project's principles is *prevented* at the **Definition of Ready** — the approach is agreed and principle-aligned in the inner space *before* a line is written — and *caught* at the **required review** (the merge gate). Alignment-with-principles is an explicit Definition of Done criterion: the machine-checkable parts become CI rules; the judgment part is the reviewer's call.

**4. Three roles — Owner, Maintainer, Developer.**
Authority is tiered: **Owners** decide everything (roadmap, Epics, Decisions, final say); **Maintainers / Tech Leads** are the review gate (review and merge others' work); **Developers** do the work (file tasks, build, propose). The authority that matters — the merge — is held in GitHub, not on the YouTrack board; the board *reflects* reality and is kept honest — it cannot show more progress than git and the release history back. The full capability matrix and the work lifecycle live in [`../workflow.md`](../workflow.md).

## Consequences

**Positive.**

- The public repo stays self-sufficient and the model degrades gracefully: a contributor without YouTrack access is missing nothing required, and works via GitHub as normal.
- "Done" means the same thing for everyone, because it is anchored to objective, mostly machine-verified criteria (a merged PR) — not to who did the work, nor to a board column anyone can drag.
- The shape is standard open source (Owner / Maintainer / Developer), so it scales from solo to a small team without a redesign.

**Negative / costs.**

- Alignment-with-principles is the one thing that cannot be fully mechanized — it depends on reviewer judgment. Front-loading (the Definition of Ready) is the mitigation, but it is process, not enforcement.
- Some enforcement only earns its keep once there is a second person; until then it is *designed but not switched on* (see "What is wired today" in [`../workflow.md`](../workflow.md)).

**Neutral.**

- At current solo scale the model is largely *designed, not yet exercised*: the author and the AI assistant act as Owners and self-merge; the roles below Owner have no occupants yet. It is recorded now so the decisions are not lost and so onboarding a teammate becomes a configuration step, not a redesign.

## Alternatives Considered

- **Enforce authority on the YouTrack board** (role-gated stage transitions). Rejected as the *primary* control: the board is poll-only and a board move is just a claim; the real gate is the GitHub merge, which is event-driven and enforced. A board rule is worth keeping only for *integrity* (a stage cannot claim more than git backs), not for authority.
- **Trust a contributor's local checks** (treat "I ran security/QA" as sufficient). Rejected: local checks are disable-and-revert with no trace; trust must rest on what re-runs on our side.
- **A flat role model** (everyone a Contributor, distinguished only at the merge). Workable at two people, but it does not express the Maintainer / Tech-Lead review tier the project will need, and would force a later migration; the three-tier model is the standard shape and avoids that.

## Related Decisions

- [ADR-004](0004-reader-not-oracle.md), [ADR-006](0006-no-isvalid-boolean.md) — the kind of *principle* alignment the review gate exists to protect.
- [ADR-011](0011-open-source-at-public-release.md) — why governance is public (git), not inner.

## Related Documents

- [`../workflow.md`](../workflow.md) — the operational detail: role × capability matrix, board stages, Definition of Ready / Done, per-type flows, platform role mapping.
- [`.claude/rules/youtrack.md`](../../.claude/rules/youtrack.md) — the capability-gated activation rule (the inner/outer access mechanism).
- [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md), [`../contributor-map.md`](../contributor-map.md) — the contributor-facing entry points this model sits behind.
- The 2026-06 YouTrack deep review (internal working note, not committed) — the investigation this decision resolves.
