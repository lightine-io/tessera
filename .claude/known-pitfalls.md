# Known Pitfalls

This document captures specific failure modes that have surfaced during the project's design phase. They are not theoretical risks — they are mistakes that have actually been made and corrected. Each entry describes the pitfall, why it happens, and what to do instead.

The point is to surface these patterns so they are caught early in implementation rather than relived. Future contributors (human or AI) reading this should leave with concrete things to watch for.

---

## Drifting Toward Oracle Behavior

**The pitfall:** Adding "convenience" features that subtly cross from reading data to making trust decisions.

**What it looks like:**
- An `isValid` boolean on result types ("just to make it easier")
- Auto-correction of common OCR errors ("the user clearly meant `O` not `0`")
- Inferring a transliteration profile from the issuing state ("we can tell what they meant")
- Silently filtering out validation failures the SDK considers "not important"
- Returning `null` instead of data when something failed validation

Each of these *feels* helpful. Each of them violates Principle 1 (Reader, not oracle). The SDK does not decide what valid means, what the user meant, or what the consumer should do.

**What to do instead:** When tempted to add a "smart" behavior, ask: am I making a trust decision on the consumer's behalf? If yes, stop. The consumer has context the SDK does not have. The SDK reports observations; the consumer interprets them.

---

## Embedding Version Numbers in Prose

**The pitfall:** Writing "the 0.1.0 parser handles X" or "as of 0.2.0, this changes" in feature documentation body text.

**Why it happens:** It feels precise to anchor a description to a specific version. It signals "I know exactly what I'm describing."

**What's wrong with it:** Feature documentation describes features as they currently exist. The structural marker `Available since X.Y.Z` at the top of the document already conveys version information. Embedding versions in prose creates maintenance burden — every release that touches the feature requires text edits across the document — and makes the documentation read like a changelog rather than a specification.

**What to do instead:** Write in present tense. "The parser operates in strict mode." "The generator produces conformant output." Use structural markers (`Available since X.Y.Z`, `Deprecated in X.Y.Z`) for version information. Let scope.md and the changelog handle release-specific narratives.

---

## Inconsistent Citation Formats

**The pitfall:** Switching between "ICAO 9303" and "ICAO Doc 9303" interchangeably across documents.

**Why it happens:** Both forms are technically correct. Either feels natural in context. Without discipline, both end up in use.

**What's wrong with it:** Inconsistent citation makes documentation harder to scan. A reader wonders if "ICAO 9303" and "ICAO Doc 9303" refer to different things. Search and reference tools work better with consistent terminology.

**What to do instead:**
- Use **"ICAO Doc 9303"** for citations to the document, especially when citing specific Parts (`ICAO Doc 9303 Part 3 Section 5`)
- Use **"ICAO 9303"** as an adjective for formats and compliance (`ICAO 9303 TD3 format`, `ICAO 9303-compliant`)
- Verify before drafting: which form fits the grammatical context?

---

## Type Names Without the `Mrz` Prefix

**The pitfall:** Naming an MRZ-related error or warning type without the `Mrz` prefix (e.g., `CheckDigitMismatch` instead of `MrzCheckDigitMismatch`).

**Why it happens:** When drafting in isolation, the prefix feels redundant — the context is clear. When type names later appear alongside non-MRZ types (chip data errors, camera errors), the inconsistency becomes visible.

**What to do instead:** Every MRZ-related error, warning, or validation failure type starts with `Mrz`. This convention extends to other domain-specific prefixes that may emerge (`Nfc...`, `Camera...`, `Chip...`). The prefix is part of the public API — changing it later is breaking.

---

## Forward References That Become Stale

**The pitfall:** Writing "see [Feature: MRZ Validation](https://lightine.youtrack.cloud/articles/TES-A-32) when written" in [Feature: MRZ Data Model](https://lightine.youtrack.cloud/articles/TES-A-33), then forgetting to update once [Feature: MRZ Validation](https://lightine.youtrack.cloud/articles/TES-A-32) exists.

**Why it happens:** Forward references are explicitly allowed by the project's conventions (better to point to intent than not to point at all). But they need to be revisited when the referenced document materializes.

**What to do instead:** Maintain a mental list (or, better, an explicit list in the project's issue tracker) of forward references. When the referenced document is written, update the source reference to remove the "when written" qualifier. A grep check at major milestones catches lingering qualifiers like "when written," "to be written," "will be written."

---

## Naming Specific Organizations

**The pitfall:** Mentioning specific organizations (whether intended customers, employer organizations, or anchor users) in documentation, code, or comments.

**Why it happens:** The project exists in a context. The user works for a specific agency. There are real anchor users in mind. It feels natural to mention them.

**What's wrong with it:** The project is presented as a vendor-neutral SDK. The moment a specific organization is named, the project becomes "that thing the agency is doing" rather than a community resource. This affects credibility, contribution potential, and the user's professional position (separating personal projects from government employment role).

**What to do instead:** Refer to organizations generically. "An issuing state where this character appears regularly" rather than naming the state. "The anchor consumer" rather than "the specific government agency." Standards bodies (ICAO, ISO/IEC) are an exception — they can be cited for accuracy. Specific governments and their agencies are not.

---

## Premature Modularization

**The pitfall:** Creating a new module for every concept rather than starting with internal packages within existing modules.

**Why it happens:** Modular thinking is encouraged by Principle 3, and modular structure looks clean in architecture diagrams. The temptation is to express modular thinking by creating modules.

**What's wrong with it:** Modules carry costs (build configuration, dependency management, public API surface, documentation). Creating modules for concepts that always travel together is paying these costs without benefit. Future-you (or future-Claude) will have to maintain the module-level overhead even when no module-level concern is at play.

**What to do instead:** Apply Principle 11 — internal packages first. A new feature starts as an internal package within an existing module, with a clean public API surface. Promote to a standalone module only when independent reuse, evolution, testing, ownership, shipping, or optional inclusion clearly applies. The internal-package boundary makes promotion mechanical when justified.

---

## Drafting Long Stretches Without Checking In

**The pitfall:** Working through a substantial piece of content in one go, then presenting it as a finished thing, only to discover that a misunderstanding in step one made everything since wrong.

**Why it happens:** Momentum. It feels productive to keep going. Stopping to check in feels like interruption.

**What's wrong with it:** Errors compound. A small misunderstanding in step one, undiscovered, shapes step two and step three. By step five, untangling requires revisiting everything. The user has explicitly said they prefer shorter cycles with verification over longer cycles with surprises.

**What to do instead:** Check in at natural milestones. After major decisions, before substantial drafting, when a question arises that the user might want to answer differently than your default. The cost of a check-in is low; the cost of redoing work after a missed check-in is high.

---

## Over-Specifying What Cannot Be Verified Yet

**The pitfall:** Including detailed specifications in documentation for things that have not been validated against real implementation, real data, or real consumer feedback.

**What it looks like:**
- Listing every error type the parser might produce (some will not exist; some will exist that we did not predict)
- Specifying exact threshold values without ever measuring (e.g., "130 years" for date plausibility)
- Describing API method signatures down to the parameter name (the implementation may diverge)

**What's wrong with it:** Premature specification creates documentation that disagrees with reality. Worse, it locks in choices that should be open until measurement or implementation tells us what works.

**What to do instead:** Specify the *structure* (the contract, the invariants, the principles) and acknowledge what the implementation will reveal. Phrases like "the catalog is discovered through implementation and testing" or "specific thresholds are tuned during implementation" honor Principle 4 (Honest about what we know).

---

## Treating Examples as Specifications

**The pitfall:** Example code in documentation gets read as if it were the actual API contract, leading to over-specification and inflexibility.

**Why it happens:** Examples are concrete and easy to read. Readers naturally treat them as authoritative.

**What's wrong with it:** Examples illustrate; they do not specify. The actual API may differ from the example in details that matter. If readers anchor too hard on examples, the implementation becomes constrained by accidentally-specified details.

**What to do instead:** When code blocks appear in documentation, mark them as illustrative explicitly. Use the phrase "illustrative shape" or "Kotlin-flavored example." Note that actual class names, method names, and parameter shapes are decided at implementation time. Update documentation with the final shapes once implementation lands.

---

## Auto-Generated Worktree Branch Names Leaking Into PRs

**The pitfall:** Pushing a branch named `claude/confident-albattani-6f0935` (or similar Claude Code default) to GitHub. The first PR opened on the project hit this — the branch name on the PR list told reviewers nothing about what the PR contained.

**Why it happens:** Claude Code creates worktrees with auto-generated names (`claude/<docker-style-adjective-name>-<hex>`). The branch checked out in the worktree inherits that name. If nobody renames it before pushing, the random name is what GitHub displays.

**What to do instead:** Rename the branch as the first or second step of any session that will result in a PR. From inside the worktree:

```sh
git branch -m claude/<auto-name> feature/<descriptive-name>
```

The worktree directory path is unchanged; only the branch reference moves. Aim for the branch name to convey what the PR is about: `feature/validator`, `docs/align-illustrative-shapes`, `fix/check-digit-overflow`.

The branch lifecycle is documented in `.claude/git-workflow.md`.

---

## Treating Doc Tensions As Interpretive Rather Than Real

**The pitfall:** When two documentation files commit to slightly different shapes for the same thing (e.g., one doc names a type `MrzParseError` while another implies `MrzError` is the only sealed root), it's tempting to "interpret" one in light of the other and silently work around the gap.

**Why it happens:** Both docs are written carefully. Both seem authoritative. Picking one to "win" feels presumptuous. So the implementation tries to satisfy both via creative interpretation — which usually means satisfying neither.

**What's wrong with it:** The next reader hits the same tension and re-derives the same workaround, possibly differently. The docs stay misaligned. Implementation drifts from explicit commitments. Per Principle 4 (Honest about what we know): if two docs disagree, that disagreement is information that needs resolving, not papering over.

**What to do instead:** When you notice a doc tension during implementation, surface it explicitly. Lay out the readings, propose a resolution (which doc to update, or whether to introduce intermediate types that satisfy both), and either get the user's call or make the call yourself with reasoning recorded. Real examples that have happened on this project:

- [Architecture](https://lightine.youtrack.cloud/articles/TES-A-9) "country and nationality codes" in `domain` vs. [Feature: MRZ Data Model](https://lightine.youtrack.cloud/articles/TES-A-33) `CountryCode` consuming a lookup table in `mrz-core` → resolved by ADR-012 introducing the rule that recognition-bearing value classes live with their tables.
- [Feature: MRZ Data Model](https://lightine.youtrack.cloud/articles/TES-A-33) `MrzParseError` vs [Feature: MRZ Error Taxonomy](https://lightine.youtrack.cloud/articles/TES-A-28) flat `MrzError` → resolved by introducing `MrzParseError` and `MrzGenerationError` intermediate sealed types under `MrzError`, plus updating the taxonomy doc with a "Sub-Categorization by Operation" section.

Each resolution required a written decision. Each became a small commit alongside the implementation slice that surfaced it. Do not silently work around tensions; resolve them.

---

## Carrying Forward A Recap's Interpretation Without Checking The Primary Doc

**The pitfall:** A recap, summary, or session-handoff note describes the state of the project at a moment in time. Later, a new decision needs to be made and the recap gets used as the source of truth. But the recap is a *derived* source — an interpretation of the primary docs ([Scope](https://lightine.youtrack.cloud/articles/TES-A-62), ADRs, the project's issue tracker, feature docs). It can drift, subtly over- or under-state what the primary docs actually say, or fall behind as the primary docs evolve. Acting on a drifted recap without re-checking the primary doc propagates the drift forward into the new decision.

**Why it happens:** Recaps are easier to read than primary docs. They compress decisions into digestible narratives. Once you've read the recap, going back to the original docs feels redundant. The general principle of trusting the doc system (per the `feedback_trust_existing_docs.md` memory) is healthy — but it applies to *primary* sources, not to derived ones, and the same level of trust should not extend to recaps and summaries when the cost of being wrong is high.

**What's wrong with it:** Decisions made on a drifted recap inherit the drift. If ADR-007 backward-compatibility will lock the decision at `0.1.0`, the drift becomes a permanent error. Fixing the recap after the fact does not undo the decision the recap shaped.

**Real example.** In May 2026, the pre-`0.1.0` recap stated that 0.1.0's readiness required mobile-target enablement (Android + iOS). This shaped the framing of a Path-A vs Path-B vs Path-C 0.1.0 strategy discussion — Path B looked materially heavier because it appeared to require Android/iOS target work. The user surfaced the question "are we sure Android/iOS are in 0.1.0?" prompting a direct read of [Scope](https://lightine.youtrack.cloud/articles/TES-A-62). The primary doc said target enablement is per-release-need: mobile activates in 0.2.0 alongside camera reading. The recap had drifted from [Scope](https://lightine.youtrack.cloud/articles/TES-A-62). The fix ([PR #33](https://github.com/lightine-io/tessera/pull/33)) was 6 lines of [Scope](https://lightine.youtrack.cloud/articles/TES-A-62) tightening to prevent recurrence; the cost of *not* catching the drift would have been weeks of incorrect 0.1.0 planning.

**What to do instead:** When acting on a recap, summary, or session-handoff that will shape a foundational decision, read the underlying primary doc first. If they agree, proceed. If they don't, surface the drift explicitly, fix the derived source, and re-base the decision on the primary doc. This is the operational pattern named in `CLAUDE.md` under "Verification Before Acting" ("Before committing to a foundational decision...") and described in full in `.claude/working-patterns.md` under "Pre-commitment alignment check."

The trigger for slowing down is *cost of being wrong is high*, not *I want to feel thorough*. Routine implementation slices, doc fixes within established conventions, and anything cheaply reversible do not need this check. Foundational decisions — anything ADR-007 will lock at `0.1.0`, tech-stack calls, scope-defining wording — do.

---

## Tagging A Release Before Reality Matches The Claim

**The pitfall:** Tagging `0.1.0` (or any release) when the documentation describes things that are not yet implemented. Feature docs claim `Available since: 0.1.0` based on the project's roadmap, but the *roadmap* is aspirational until shipped.

**Why it happens:** Once enough work has accumulated, it feels natural to tag a release as a milestone. The docs already say "Available since 0.1.0," so tagging seems like just confirming what's true.

**What's wrong with it:** Pre-1.0, the project follows ADR-007 (strict backward compatibility from 0.x onward). Tagging `0.1.0` creates real consumer commitments — anything claimed `Available since 0.1.0` becomes part of the API consumers may depend on. If features are claimed but not implemented, the release ships with a documentation lie that's hard to fix later (renaming or repurposing claimed-but-empty surfaces is a breaking change).

For Tessera specifically: [Scope](https://lightine.youtrack.cloud/articles/TES-A-62) defines `0.1.0` as "Pure parsing, generation, and validation for all ICAO Doc 9303 MRZ formats. Includes lookup tables, transliteration profiles, error taxonomy, and pluggable telemetry." That's a substantial scope. Tagging `0.1.0` before generation, validation, transliteration, country code tables, and the other format parsers exist would lock in claims that aren't backed by implementation.

**What to do instead:** Before any release tag, audit the scope-vs-implementation gap. Either (a) implement everything claimed for the release, or (b) re-scope the release downward and update the affected docs. The project's Code/Doc Alignment Recap process (see `RECAP-CODE-DOC-ALIGNMENT-*.md` working notes) is the right tool for this; run it before tagging.

Pre-release work (before `0.1.0` is tagged) is the safe zone for breaking changes and re-scoping. After the tag, it's much costlier.

---

## `field` As A Constructor Parameter Name In A Class With A Custom Getter

**The pitfall:** Naming a constructor parameter `field` in a Kotlin class that defines a property with a custom getter using string interpolation. The getter looks like:

```kotlin
public data class MrzCheckDigitMismatch(
    val field: MrzField,
    // ...
) : MrzValidationError() {
    override val description: String
        get() = "Check digit for $field at position $position"  // ← compiles?
}
```

Compiles to: `e: ... Property must be initialized.` — pointing at the `description` property declaration, not at the getter.

**Why it happens:** `field` is a soft keyword in Kotlin: inside a property's getter or setter, it refers to the property's backing field. When the getter contains `$field` in a string interpolation, the parser binds `$field` to the backing-field reference of the *enclosing property* (`description`), not to the constructor parameter named `field`. `description` has a custom getter and no backing field, so the compiler concludes the backing field is unused and the property has no initializer — hence "Property must be initialized."

The error is misleading: the property is fine; the getter is the problem; the parameter name is the trigger.

**What to do instead (in order of preference):**

1. **Rename the parameter.** Most explicit, no future surprises. The semantic name is "the MRZ field whose check digit failed" — `field: MrzField` is the natural shape, but if it triggers this, options like keeping the parameter name `field` and qualifying with `${this.field}` in the interpolation work. Either is a reasonable resolution.
2. **If renaming is undesirable** (e.g., the parameter name `field` is part of an established API): qualify with `${this.field}` in any interpolation inside a custom getter. This avoids the soft-keyword binding without renaming the parameter.

The MrzCheckDigitMismatch type currently uses the qualifying approach: `${this.field}` in the description getter. Keep this rule in mind for any future error/data class with a parameter that could shadow the `field` soft keyword.

---

## Claiming a Gap Without Verifying the Files

When reviewing for missing or stale content, it is tempting to assert "X is missing" or "Y is wrong" from memory or from a derived summary. The "a no-issues-found verification is suspicious" rule has a mirror: **a *found* issue is just as suspicious until verified against the actual file.** In the 0.2.0 work, two confident "this is a gap" calls were wrong — [Reading Risks](https://lightine.youtrack.cloud/articles/TES-A-11) already had a Live-Camera section, and the JDK-17 daemon pin (`gradle-daemon-jvm.properties`) already existed — caught only because the files were re-read before acting. Asserting a gap that isn't there wastes a fix cycle and risks "correcting" something already right.

**Fix:** Before asserting something is missing, wrong, or stale, open the actual file (or run the actual check) and confirm. Found-issues need the same verification as no-issues. State confidence honestly: "I believe X is missing" vs "X is missing (verified at `file:line`)."

---

## Verifying Against Transient State Instead Of The Committed Source Of Truth

**The pitfall:** Asserting that something is true, done, or removed after checking a *transient* form of the state — the working tree at this moment, a machine-local file, the first location you guessed — rather than the *durable, authoritative* form that actually governs: what is committed and tracked, what survives a fresh clone, what the build or tool actually reads. The check happens, so it feels verified — but the wrong state was checked.

This is the sibling of "Claiming a Gap Without Verifying the Files" above. That entry says *verify before asserting*. This one says: **even when you verify, confirm you are looking at the state that matters.** A check against a snapshot that won't survive a clone — or against the wrong location — is a check that lies.

**Why it happens:** The transient state is the one in front of you. The working tree, the current machine's filesystem, the first path you looked at — immediate and cheap to inspect. The authoritative state takes a deliberate extra step (`git show HEAD:path`, `git ls-files`, reading the config the build actually consumes, checking the tool's default location rather than a guessed one). Under momentum, the immediate check quietly substitutes for the authoritative one.

**What's wrong with it:** A decision recorded only in a machine-local note (gitignored memory, a `.handoffs/` file, an uncommitted edit) does not survive a fresh clone — a new-machine session re-derives it wrongly or contradicts it. An assertion about "what's installed/removed" based on one guessed path is wrong when the real artifact lives elsewhere. The error is invisible *on this machine, in this working tree*, and surfaces only when the durable state is what gets consulted — a new contributor, a fresh clone, CI — which is exactly when it is most expensive to discover.

**Real examples (0.2.0 Android-environment work).**
- The `compileSdk 37` decision lived only in gitignored machine-local memory while the committed ADR still said `compileSdk 36`. Checked against memory it looked settled; against the committed ADR it was a contradiction that would mislead any fresh-clone session. Fix: commit the decision to the ADR so it survives a clone.
- An SDK-provenance claim ("removed — it's not in `/Applications`") was wrong: the SDK was a Toolbox install at `~/Library/Android/sdk`. One guessed location stood in for "where the SDK authoritatively lives."

**What to do instead:** Before asserting state, identify the *authoritative* form and check that one:
- For a **decision** — the committed doc (ADR / [Scope](https://lightine.youtrack.cloud/articles/TES-A-62) / the project's issue tracker), not a handoff, recap, or machine-local memory. If a decision matters and lives only in a machine-local note, *that's the bug*: commit it.
- For **what's tracked** — `git ls-files` / `git show HEAD:path`, not the working tree of the moment.
- For **what a tool reads** — the actual config it consumes (the SDK's default location, the `local.properties` the build reads), not a path you assumed.

State confidence with its source: "committed in the ADR" beats "I recall deciding it." This complements the "Pre-commitment alignment check" in `.claude/working-patterns.md` (verify primary docs before foundational decisions): that pattern is about *which document* is authoritative; this pitfall is about *which version of the state* — durable, not transient.

---

## Reading a Whole Credential-Bearing File

Some files hold secrets even though you need only one non-secret line — notably `~/.gradle/gradle.properties`, which carries the PGP signing key + passphrase and the Sonatype token alongside ordinary Gradle settings. Reading the whole file pulls those secrets into the session transcript (an unintended, persistent exposure surface) when a single `grep` would have answered the question. This happened during the 0.2.0 work — reading the file to check one `installations.paths` line exposed the signing key.

**Fix:** Treat credential-bearing files as **grep-only** — extract the specific non-secret line (e.g. `grep installations.paths ~/.gradle/gradle.properties`), never Read or `cat` the whole file. Know which files are secret-bearing before touching them (`~/.gradle/gradle.properties` is flagged in the `reference_local_jdk_setup` memory). If secrets do reach the transcript, surface it immediately and let the user decide on rotation.

---

## Cocoa Delegates Held Weakly + Kotlin/Native GC = Vanishing Callbacks

A Cocoa API that takes a *delegate* almost always holds it **weakly** (the framework assumes the caller keeps it alive). In Swift that's automatic — ARC retains your strong reference deterministically. In **Kotlin/Native it is not**: bridged Objective-C objects are reclaimed by the **GC**, not ARC, so a delegate with no strong Kotlin reference is alive only until the next GC pass — after which the framework's weak pointer goes `nil` and the callbacks silently stop. This cost most of two sessions on `AVCaptureMrzScanner`: `AVCaptureVideoDataOutput.setSampleBufferDelegate` was handed a delegate created **inline** (no field, no `val` kept alive), so after a few seconds the GC collected it and the camera stopped delivering frames — with no error, no interruption, and **zero dropped-frame callbacks** (there was no delegate to deliver to). The stall *looked* like a buffer-pool / memory-pressure problem because it struck sooner under heavier per-frame allocation (more allocation → GC fires sooner → delegate dies sooner), which sent the investigation chasing the wrong layer (copying buffers, IOSurface backing, OCR-rate throttling) for two sessions.

**Fix:** Hold a **strong reference for the whole lifetime of the thing using it** — a class field (`private var captureDelegate: …`), set when wiring up, cleared on teardown. The decisive diagnostic was forcing `GC.collect()` every frame: it reproduced the stall after a *single* frame, pinpointing the GC as the trigger and the weakly-held delegate as the only GC-reclaimable, callback-critical object. **General rule:** when bridging to any Cocoa API that stores a weak delegate/observer/target (capture delegates, `NSNotificationCenter` block tokens, `CADisplayLink`/timer targets, KVO observers), assume nothing retains it for you and keep an explicit strong reference on the Kotlin side. Verify on a *device over time* — a GC may not fire on the simulator or in a short run, hiding the bug. (Root cause + the JetBrains [ARC-integration](https://kotlinlang.org/docs/native-arc-integration.html) reasoning are in the resolved the project's issue tracker camera-stall entry.)

---

## Dangling Working-Tree Changes Riding Across Branches

**The pitfall:** Leaving an uncommitted working-tree change (a `.gitignore` edit, a stray new file) at session end, so it rides along into the *next* session's unrelated feature branch — where it gets mixed into a PR it doesn't belong in, muddying that PR's scope, or is silently dropped when the branch is reset.

**Why it happens:** Momentum at session end. The change feels too small to warrant its own commit, so it gets left "for later." The next session checks out a fresh branch for unrelated work and the dangling change comes with it, now decoupled from the context that explains it.

**Real example.** The `.gitignore` additions for the working-note folders (`.reviews/`, `.discovery/`, `.plans/`) sat uncommitted across a session boundary and had to be rescued into their own PR ([#123](https://github.com/lightine-io/tessera/pull/123)) the next session — after they had already ridden along on unrelated branches.

**What to do instead:** Commit (or stash, or deliberately discard) every working-tree change before ending a session or switching to unrelated work. A dangling change belongs to *some* logical unit — give it its own small focused commit/PR up front rather than letting it contaminate the next branch. `git status` should be clean (or deliberately staged for the work at hand) at every branch switch. This is the operational form of the project's small-focused-PR preference: a change with no home of its own is a change waiting to land in the wrong one.

---

## Reaching for the Raw Vendor Tool When the Rule Can't Reach

**The pitfall:** A project rule prescribes a wrapper/driver over a raw vendor tool — "drive the Android SDK via the `android` CLI, not raw `sdkmanager`/`avdmanager`"; "drive iOS via the Xcode MCP, not raw `xcodebuild`/`xcrun simctl`." But `.claude/rules/` rules are **path-scoped to file edits** — they load when you edit a matching file, not when you run a bash command. In a session that only *runs commands* (no matching file edit), the rule never fires, and habit reaches for the raw vendor tool.

**Why it happens:** The rule exists, so it *feels* covered. But the trigger condition (editing a path-matched file) doesn't occur during pure command-line work, so the guidance is structurally absent at the exact moment it's needed. Habit fills the gap.

**Real example.** The `mobile-dev-workflow` rule prescribes the Android CLI, but it couldn't fire on a bare `sdkmanager` invocation during the 0.2.0 work, so raw `sdkmanager` got used out of habit. Fixed structurally by the [#127](https://github.com/lightine-io/tessera/pull/127) hook (`scripts/prefer-dev-wrappers.sh`), a PreToolUse nudge on `Bash` that steers raw `sdkmanager`/`avdmanager`/`xcodebuild`/`xcrun simctl` toward the prescribed driver (with a `# raw-ok` override for the deliberate exception).

**The meta-lesson — match the mechanism to the failure surface.** A lapse at the *command* layer needs a *hook* (PreToolUse on `Bash`), not a path-scoped rule: a rule that can only fire on file edits is structurally blind to command-line habits. When you catch willpower failing on a command you "know" a rule covers, check whether the rule can even fire there. If not, that's a hook-shaped gap, not a discipline problem. (This is "Prescribe the path, prohibit the border" from `working-patterns.md` applied to enforcement placement: the border has to be enforced where the crossing actually happens.)

**What to do instead:** Use the prescribed driver (now hook-enforced for these tools). And when designing enforcement, pick the mechanism whose trigger actually covers the moment the mistake happens — don't write a file-edit rule to guard a command-line act.

---

## Patching One Handoff in Place Across a Long Session

**The pitfall:** A single long (often multi-day) session keeps *editing the same `SESSION-HANDOFF` file in place* as work progresses, instead of writing a fresh superseding handoff. Each in-place edit patches prose without re-deriving it, so contradictions accumulate: a "done" item still listed as blocked, a "Next Session Should" pointing at finished work, a moved file's old path, a count that no longer matches reality.

**Why it happens:** Editing the existing handoff feels cheaper than writing a new one, and each individual edit looks locally correct. But the handoff template assumes you are *writing* a handoff (regenerate the sweep, snapshot the state), not *re-editing* one — so repeated patches drift from the live state without any single edit looking wrong.

**Real example (2026-06-13).** The `consumer-docs-track` handoff was edited ~8 times across a 06-12→06-13 session and drifted in four spots at once: the "Standing Obligations" section said "exactly one" open item when the sweep returned two (P8 had been added later); "What Is in Flight" listed B2 as both `BLOCKED` and "closed" in the same section; "Next Session Should" told the next session to do already-shipped work; and two links pointed at a review file that had been archived to `.reviews/archive/`. The headline (`⭐ START HERE`) stayed accurate, masking the frayed details. Fixed by writing a fresh superseding handoff.

**The tell:** the "Standing Obligations" section is the canary. It is defined as *sweep-derived, never remembered* — so if it disagrees with `grep -n '\- \[ \]' .plans/*.md`, the whole handoff has drifted and is overdue for a rewrite.

**What to do instead:** `/clear` between distinct tasks (CLAUDE.md Session Discipline) so handoffs stay session-sized. When a long session *has* materially moved on, write a **fresh handoff with a `Supersedes:` line** rather than patching the old one a ninth time — `sort -r | head -1` makes the newest win, so the stale one becomes inert history. If you must edit in place, **re-run the sweep and re-check every status line** against reality instead of trusting the prose already there.

---

## Writing a Correcting Handoff as a Diff Instead of a Fresh Statement

**The pitfall:** A handoff that supersedes an earlier one is written as a *correction* —
"we're no longer doing X" — without restating, in plain affirmative terms, what the plan
now *is*. Because the next session reads only the latest handoff (CLAUDE.md: read the most
recent first), the affirmative plan that lived in the superseded file is invisible. The
reader reconstructs intent from the negation — and a negation under-determines the
positive, so they often reconstruct it wrong.

**Why it happens:** Superseding feels like editing: you note what changed since the prior
handoff and assume the reader carries the rest. The `Supersedes:` line and the "Superseded
This Session" section both encourage a diff mindset. But the next session never saw the
prior handoff — the diff is all they get, and a diff is not a state.

**What's wrong with it (two shapes that compound):**
- **Negation without restatement.** "The move-everything plan is parked" says what is *not*
  happening, never what *is*. The hole gets filled by whatever signal is loudest nearby —
  usually the surrounding prohibitions.
- **Tone leaking into state.** A handoff that frames a result as a *retreat* from the prior
  session's ambition ("smaller," "parked," "possibly none") reads as "we pulled back," even
  when the facts are a *success* ("we built a placement rule, and it correctly concluded
  that little should move, done deliberately"). The reader inherits the tone, not the facts:
  a **paused rollout** gets read as a **dropped approach**.

**Real example (2026-06-24 → 06-25).** The `youtrack-content-placement` handoff superseded
`youtrack-migration-and-graduation`. The prior handoff held an affirmative "Locked plan:
re-home Bucket A inner notes → YouTrack." The superseding handoff led with "migration
PARKED pending reassessment," "the real move-list is small," "most of docs/ stays in git,"
"possibly none" — and never restated the live plan (a deliberate content-placement rule,
executed one slice at a time). A fresh session read it as "the migration is cancelled /
almost nothing moves" and proposed standing down — when the real state was a healthy
approach being rolled out incrementally. The maintainer had to correct it across several
turns.

**What to do instead:**
- When a handoff carries a `Supersedes:` line, **restate the whole current plan
  affirmatively** — what we're doing, why, where it stands, how it proceeds — as if the
  reader never saw the prior handoff (they didn't). The delta belongs in "Superseded This
  Session"; the *state* belongs in START HERE.
- **Separate the approach from the rollout** — say which is paused. A parked rollout is not
  a dropped approach. (Pinned in the handoff template's Status vocabulary section, under
  PARKED.)
- **Lead with intent, end with warnings.** Don't let prohibitions be the first thing START
  HERE shows; a stack of "don't" at the top gets weighted as the headline.

This is the sibling of "Patching One Handoff in Place Across a Long Session" above — that
one is a handoff drifting from too many in-place edits; this one is a *fresh* superseding
handoff that correctly retires the old state but forgets to install the new one.

---

## Maintaining This Document

This document grows when new pitfalls are observed during ongoing work. The bar for adding an entry: *has this mistake actually been made on this project, or is it close enough that it could be?* If yes, document it concretely with the specific pattern. If no, do not add speculative pitfalls — [Reading Risks](https://lightine.youtrack.cloud/articles/TES-A-11) and the principles already cover those.

Removed entries: when a pitfall has been so thoroughly internalized that it no longer surfaces, the entry can be moved to a historical note or archived. Most entries should stay — the discipline of remembering is the value.
