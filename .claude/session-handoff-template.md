# Session handoff: pointer template

A handoff is a pointer, not a container: 10 content lines or fewer. Durable knowledge
lives in its home (the issue, the ADR, the CHANGELOG, the KB), written the moment it was
learned. If something durable is only in your head at handoff time, put it in its home
first, then point at it.

**Where:** `.handoffs/SESSION-HANDOFF-YYYY-MM-DD-HHMM-<slug>.md` in the main checkout
(gitignored; worktree sessions use the same folder). Time is current UTC, four digits;
the slug is short kebab-case.

**When:** at the end of any session with commits, YouTrack writes, or a mid-task stop.
The Stop hook asks once. Trivial sessions skip it.

The SessionStart banner injects the three sections below verbatim, so keep these exact
headings (renaming them means changing `scripts/session-banner.sh` and its test in the
same PR).

```markdown
# Session handoff: <date> <UTC time>: <slug>

## START HERE
- Develop card: <TES-n>: <one-line state>. Next action: <one line>.
- Uncommitted state: <branch + files, or "clean, on main at <sha>">.

## Next Session Should
1. The Kanban meeting; then <the one move the board does not show, or "pull per the guide">

## Things to Watch For
- <session-local gotchas only; nothing durable belongs here, and nothing the board shows>
```
