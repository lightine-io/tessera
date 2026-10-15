---
name: board-check
description: Check the TES board for drift from the issue style and from GitHub. Run at the start of a session that will touch the board, after a batch of issue edits, and at the release gate. Read-only; reports findings and fixes only what the maintainer approves.
---

# Board check

The board is only useful if it is true. This check finds the drift that has happened before:
fields missing, Done issues with no Outcome, titles full of code names, and stages that
contradict GitHub. The rules it checks are in [`.claude/issue-style.md`](../../issue-style.md).

Use the official YouTrack connector (the tools whose `update_issue` takes `customFields`).
Never trust a write's success reply: read the issue back. There is no local token, so no REST.

## Steps

1. **Fields.** Run each query with the connector's `search_issues`. Each should return nothing.
   - `project: TES has: -Type`, `has: -Stage`, `has: -Area`, `has: -Platform`
   - `project: TES Stage: -Done has: -Release` (open issues need a release)
   - `project: TES Stage: Done Effort: S, M, L` (effort is for open issues only)
   A query that returns nothing is suspect: run one positive control, such as
   `project: TES Area: UI`, and confirm it returns issues.
2. **Outcome blocks.** `project: TES Stage: Done updated: {minus 14d} .. *`. For each, read it
   with `get_issue` and check the description starts with `> **Outcome**`.
3. **Titles.** For the issues listed in steps 1 and 2, and any open issue created in the last
   14 days (`created: {minus 14d} .. *`): flag a title with backticks, `camelCase` words, file
   names, or issue numbers.
4. **Stages against GitHub.**
   - Open issues in `Review`: `gh pr list --state all --search "TES-<n>"` must show an open PR.
     A merged PR means the issue should be `Staging`, `Test`, or `Done`.
   - Issues in `Backlog` or `Develop` updated in the last 30 days: a merged PR naming the issue
     means the stage is stale.
   - `Staging` issues: if their work appears under a released CHANGELOG version, they are `Done`.
5. **Report** one table: issue, problem, proposed fix. Apply fixes only with the maintainer's
   go, then read each fixed issue back.
