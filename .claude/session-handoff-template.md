# Session Handoff — Pointer Template

A handoff is a **pointer, not a container**: ≤10 content lines. Durable knowledge
lives in its home — the issue, the ADR, the CHANGELOG, the KB — written the moment it
was learned (constitution, Work Loop step 5). If something durable is only in your
head at handoff time, put it in its home first, then point at it.

**Filename:** `SESSION-HANDOFF-YYYY-MM-DD-HHMM-<slug>.md` in `.handoffs/` (gitignored,
never committed). Time = current UTC, four digits; slug = short kebab-case.

**Write one** at the end of any session with commits, YouTrack writes, or a mid-task
stop (the Stop hook enforces this once per session). Trivial sessions skip it.

The SessionStart banner injects the three sections below verbatim — **keep these exact
headings** (renaming requires updating `scripts/standing-obligations.sh` and its test
in the same change).

---

```markdown
# Session Handoff — <date> <UTC time> — <slug>

## ⭐ START HERE
- Focus: <TES-n> — <one-line state>. Next action: <one line>.
- Uncommitted state: <branch + files, or "clean, on main at <sha>">.

## Next Session Should
1. <the single default starting move>

## Things to Watch For
- <session-local gotchas only — nothing durable belongs here>
- Process corrections this session: <N> (KPI — target 0; each one is also an issue)
```
