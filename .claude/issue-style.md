# Issue style

How every issue on the `TES` board is written. Decided by Padre in TES-194 (2026-10-15).
The board check (`.claude/skills/board-check/`) verifies it. Old comments are never edited.

## Titles

- Say what a reader would notice. Use plain words. Under 60 characters when you can.
- Name the problem or the result, not the fix.
- No code names, class names, or issue numbers. They go below the line.
- Sentence case. No "Bug:" prefix. The Type field says it.
- One issue, one outcome.

Good:
- Android scanner can freeze the screen while it reads text
- iOS scanner shows no hint when the camera is blocked
- Passport numbers with a filler character read back wrong

Bad:
- `MrzCameraAnalyzer` ANR on frame backlog (code name, no outcome)
- Refactor CI matrix to use reusable workflow (a fix, not an outcome)
- Docs (too vague to tell which problem)

Better CI title: "Pull requests wait too long for the iOS check".

## Description (all types)

Plain top, then `---`, then technical details. The top must make sense to someone who has never opened the code.

**Bug**
```
**Problem:** What goes wrong, in one or two sentences.
**Why it matters:** Who is hurt, and how badly.
**Done when:**
- [ ] A check anyone can run and see pass
---
**Where:** files, modules
**Repro:** numbered steps, device, SDK version
**Expected / actual:**
**Notes:** logs, suspects (mark guesses as guesses)
```

**Task**
```
**Problem:** What is missing or poor today.
**Why it matters:**
**Done when:**
- [ ] ...
---
**Where:**
**Approach:** only if it is already chosen
```

**Decision**
```
**Question:** What must be decided.
**Why it matters:** What waits on it.
**Done when:** The choice is written here and in the ADR.
---
**Options:** each with one line of cost and benefit
**Lean:** mine, and why. The call is Padre's.
**Consequences:** what gets easier, what gets harder
```

**Epic**
```
**Goal:** The result, in one sentence.
**Why it matters:**
**Done when:**
- [ ] A measurable end state
---
**In scope:** 
**Out of scope:** (with a reason each)
**Children:** list of issues, each with a size
```
An epic holds about 3 to 10 children. Check each child against the goal now and then. Move off-theme ones out.

## Outcome block (Done issues)

Add at the very top of the description, then a blank line:

```
> **Outcome**
> What changed, in one line.
> Where it shipped (PR, release).
> What was left out or follows (issue link, or "nothing").
```

Old comments stay as they are.

## Comments

Only for a decision, a result, or a blocker. Three lines at most. Start with the word: Decision, Result, or Blocked. Put the next step in the last line. Progress logs go to commits, the PR, or the handoff. If a comment changes the plan, update the description too.

## Fields

Type (built in): Bug, Task, Decision, Epic.

**Area** (one value):
- Core: MRZ parsing, validation, generation
- Camera: capture and text recognition shared logic
- UI: the ready-made scanner screens on any platform (Platform says which)
- Docs: guides, API docs, examples
- Process: how work is done, rules, hooks, board
- CI: builds, checks, publishing pipeline

No new Area is needed now. Add one only when 5 or more open issues do not fit.

**Platform** (one value):
- Android
- iOS
- JVM
- All

**Release** (one value):
- 0.5.x: patch line, shipping now
- 0.6.0: next planned minor
- 1.0: needed before 1.0
- Later: wanted, no date
- Unplanned: not yet looked at

**Effort** (one value, a rough size, never days):
- S: under half a day, one place, low risk
- M: about a day or two, a few places
- L: more than that, or unclear. Split it before starting if you can.

Do not add or sum sizes. The board check (`.claude/skills/board-check/SKILL.md`) looks for missing fields, missing Outcome blocks, code names in titles, and stages that contradict GitHub.
