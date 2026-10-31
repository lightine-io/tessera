---
name: board-check
description: Check the TES board for drift from the issue style and from GitHub. Run at the start of a session that will touch the board, after a batch of issue edits, and at the release gate. Read-only; reports findings and fixes only what the maintainer approves.
---

# Board check

The board is only useful if it is true. This check finds the drift that has happened before:
fields missing, Done issues with no Outcome, titles full of code names, and stages that
contradict GitHub. The rules it checks are in [`.claude/issue-style.md`](../../issue-style.md).

This is the deeper audit behind the Kanban meeting (`.claude/how-we-work.md`), not a
replacement for it.

Use the official YouTrack connector (the tools whose `update_issue` takes `customFields`).
Never trust a write's success reply: read the issue back. There is no local token, so no REST.

## Steps

1. **Fields.** Run each query with the connector's `search_issues`. Each should return nothing.
   - `project: TES has: -Type`, `has: -Stage`, `has: -Platform`
   - `project: TES Stage: -New, -Rejected has: -{Class of service}`
   - Goal on product cards (a card with a Service; process work has neither):
     `project: TES Stage: Options, Next has: Service has: -Goal`
   - Due Date: `project: TES {Class of service}: {Fixed Date} has: -{Due Date}`
   A query that returns nothing is suspect: run one positive control, such as
   `project: TES Area: UI`, and confirm it returns issues.
2. **Outcome blocks.** `project: TES Stage: Done updated: {minus 14d} .. *`. For each, read it
   with `get_issue` and check the description starts with `> **Outcome**`.
3. **Titles.** For the issues listed in steps 1 and 2, and any open issue created in the last
   14 days (`created: {minus 14d} .. *`): flag a title with backticks, `camelCase` words, file
   names, or issue numbers.
4. **Neutral wording.** The board records what was decided and why, never who: no personal names
   or roles as actors, no opinion words in titles. For each name in `.claude/board-names.local`
   (gitignored, one per line, kept only on the maintainer's machine; skip this half when it is
   missing), `project: TES <name>` must return nothing. Scan titles for opinion words:
   `project: TES summary: awkward, ugly, messy, bad, terrible, silly`. All should return nothing. Decision credit in comments and text: `project: TES "Decision (maintainer"` must return nothing.
5. **Stages against GitHub.**
   - Open issues in `Review`: `gh pr list --state all --search "TES-<n>"` must show an open PR.
     A merged PR means the issue should be `Staging`, `Test`, or `Done`.
   - Issues in `Next` or `Develop` updated in the last 30 days: a merged PR naming the issue
     means the stage is stale.
   - `Staging` issues: if their work appears under a released CHANGELOG version, they are `Done`.
6. **Flow limits and aging.**
   - WIP limits: Next 3, Develop 1 per worker, Review 2, Test 2, Staging 8. Count
     `project: TES Stage: <name>`; blocked cards count.
   - Cards more than 14 days in Next, Develop, Review or Test (the board shows days in column).
   - `project: TES tag: blocked`: each has a Blocked comment with the reason (`get_issue_comments`).
   - Released Done cards without Fixed in: `project: TES Stage: Done has: -{Fixed in} resolved date: 2026-11-01 .. Today`; only
     cards that shipped to users count (not `no-changelog` or Decision cards).
7. **Report** one table: issue, problem, proposed fix. Apply fixes only with the owner's
   go, then read each fixed issue back.
