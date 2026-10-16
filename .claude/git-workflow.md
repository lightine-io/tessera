# Git and PR mechanics

GitHub Flow: `main` is always shippable, branches are short, every change goes through a
pull request. Prefer small focused PRs; an approved multi-step issue (a docs sweep, for
example) gets one branch and one PR at the end.

## Branch

- `feature/`, `fix/`, `docs/`, or `chore/` (the kind of change) plus the issue key, off
  `origin/main`: `git fetch origin && git checkout -b fix/TES-198 origin/main`. Outside
  contributors use the GitHub issue number: `fix/123`.
- CI (`repo · rules`) fails any other name; Dependabot's branches are exempt.
- A Claude Code worktree starts on `claude/<random>`: rename it before pushing
  (`git branch -m <new-name>`).

## Before you push

- `./gradlew spotlessApply check`.
- `CHANGELOG.md` entry under `[Unreleased]` (Keep a Changelog groups), or the
  `no-changelog` label when nothing ships. CI `changelog · entry present` checks for
  one or the other; the label also tells the board the issue is Done at merge.
- AI sessions: the push hook runs `scripts/private-content-scan.sh` (the MRZ guard, plus
  the terms in your gitignored `.claude/private-content-terms.local`). It is silent on
  pass and loud on block. Manual pushes: run it yourself. A generic word that only
  contains a term goes into `FALSE_POSITIVES` in the script, with its reason.

## Commit

Short imperative subject, blank line, then the why. Branch protection requires signed
commits only on `main`: GitHub signs the squash commit it makes at merge, so a
contributor need not sign branch commits. Release tags must be signed too (a tag
ruleset); the maintainer signs them with SSH. Commits an AI session makes end with a
`Co-Authored-By:` line naming the current model.

## Pull request

`gh pr create --base main --title "type(scope): subject (TES-n or #n)" --body-file <file>`,
with the body filled from `.github/pull_request_template.md`. The PR title, or the commit
title for a one-commit PR, becomes the squash commit. The `TES-n` lets the board follow
the PR; outside contributors use the GitHub issue number `#n`.

## Merge

Required checks (branch protection on `main`): `build & test · JVM`,
`build & test · Android`, `build & test · iOS`, `publish · smoke (empty-module guard)`,
`changelog · entry present`, `repo · rules`. `board · stage sync` is advisory. When a
workflow adds or renames a check, update the protection and this list in the same PR.

**Strategy: squash merge.** Rebase merge is allowed for a PR whose commits each stand
alone. Merge commits are off in the repository settings. This is the only place the
strategy is written down.

`gh pr merge <n> --squash --auto`, then confirm with
`gh pr view <n> --json state --jq .state` (expect `MERGED`). GitHub deletes the remote
branch; delete the local one (`git branch -d <branch>`). Remove a finished worktree from
the main checkout: `git worktree remove .claude/worktrees/<name>`.
