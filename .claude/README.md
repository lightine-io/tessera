# `.claude/` Folder

This folder contains material specifically for AI assistants (primarily Claude Code) working on this project. The content is intentionally separate from the project documentation — the human-facing docs now live in the YouTrack KB — because it serves a different audience and a different purpose.

The YouTrack KB is for humans (and AI) understanding the project itself.
`.claude/` is for AI assistants understanding how to work effectively *on* the project.

The entry point for AI assistants is the `CLAUDE.md` file at project root. That file is the orientation document and should be read first. The files in this folder are deeper material loaded as needed:

- **`working-patterns.md`** — Concrete patterns for how work happens on this project: technical patterns and collaboration patterns. Read when starting substantive work.
- **`known-pitfalls.md`** — Real failure modes that have surfaced during the project. Read before drafting significant changes.
- **`rules/content-placement.md`** — What goes into the public repo, the KB, or stays local. Read before committing anything new. (Its design-phase predecessor, `gitignore-planning.md`, is superseded and lives in `archive/`.)
- **`session-handoff-template.md`** — Template for ending a Claude Code session with a clear handoff to the next session (AI-first: state snapshot up front, standing-obligations sweep, superseded-facts section).
- **`git-workflow.md`** — Branch naming, PR flow, the private-content scan, `gh` CLI usage, commit style.
- **`rules/`** — Rules that auto-load: most are path-scoped (loaded when files matching their `paths:` frontmatter are touched); a rule *without* a `paths:` filter (`youtrack.md`) loads into **every** session. See each file's own frontmatter `description:` for what it covers — this README deliberately does not duplicate the list.
- **`skills/`** — Explicitly-invoked workflows for recurring events (dependency-upgrade cadence, pre-release tech-stack review).
- **`agents/`** — Specialized read-only review subagents (doc-consistency, security, QA-coverage), invoked via the `Agent` tool.
- **`settings.json`** — Hooks: the pre-push private-content scan, the outward gh-content scan, the screenshot block, the dev-wrapper preference, the YouTrack-write substantive marker (PreToolUse), the SessionStart standing-obligations/handoff-banner injection, and the Stop-hook session-handoff reminder.

Material in this folder is public — it is committed to the repository, which is already publicly visible. Nothing private should accumulate here. If private notes are needed during development, they live elsewhere (a personal workspace folder outside the repo) and never get committed.
