# `.claude/` Folder

This folder contains material specifically for AI assistants (primarily Claude Code) working on this project. The content is intentionally separate from the project documentation — the human-facing docs now live in the YouTrack KB — because it serves a different audience and a different purpose.

The YouTrack KB is for humans (and AI) understanding the project itself.
`.claude/` is for AI assistants understanding how to work effectively *on* the project.

The entry point for AI assistants is the `CLAUDE.md` file at project root. That file is the orientation document and should be read first. The files in this folder are deeper material loaded as needed:

- **`working-patterns.md`** / **`known-pitfalls.md`** — Retired 2026-07-12 (tombstones): content lives in `CLAUDE.md` (the constitution) and `rules/api-design.md`; full history in git.
- **`rules/content-placement.md`** — What goes into the public repo, the KB, or stays local. Read before committing anything new. (Its design-phase predecessor, `gitignore-planning.md`, is superseded and lives in `archive/`.)
- **`session-handoff-template.md`** — The ≤10-line pointer-handoff template (durable knowledge goes to its home the moment it is learned; the handoff only points).
- **`git-workflow.md`** — Branch naming, PR flow, the private-content scan, `gh` CLI usage, commit style.
- **`rules/`** — Rules that auto-load: most are path-scoped (loaded when files matching their `paths:` frontmatter are touched); a rule *without* a `paths:` filter (`youtrack.md`) loads into **every** session. See each file's own frontmatter `description:` for what it covers — this README deliberately does not duplicate the list.
- **`skills/`** — Workflows for recurring events: release-gate (pre-tag ritual), dependency-upgrade cadence, pre-release tech-stack review (invoked from the release gate).
- **`agents/`** — Specialized read-only review subagents (doc-consistency, security, QA-coverage), invoked via the `Agent` tool.
- **`settings.json`** — Hooks: the pre-push private-content scan, the outward gh-content scan, the screenshot block, the dev-wrapper preference, the YouTrack-write substantive marker (PreToolUse), the SessionStart standing-obligations/handoff-banner injection, and the Stop-hook session-handoff reminder.

Material in this folder is public — it is committed to the repository, which is already publicly visible. Nothing private should accumulate here. If private notes are needed during development, they live elsewhere (a personal workspace folder outside the repo) and never get committed.
