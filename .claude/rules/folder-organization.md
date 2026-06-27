---
paths:
  - "**/*.md"
---

# Folder and File Organization

**Folder placement reflects audience.** Folders holding *project deliverables* — source code, runnable scripts, human-facing docs — live at visible paths (`docs/`, `scripts/`, source modules, root-level files like `README.md` and `CHANGELOG.md`). Folders holding *project infrastructure* — AI-facing conventions, tool config, build state, AI working notes, maintainer reference material — live at dot-prefix paths (`.claude/`, `.github/`, `.gradle/`, `.handoffs/`, `.recaps/`, `.conformance/`, `.spec/`). The dot-prefix signals "infrastructure, not deliverable," regardless of whether the contents are committed (most of `.claude/`, `.github/`) or gitignored (`.handoffs/`, `.gradle/`, `.spec/`). Gitignore status is a separate decision driven by "is this universal across clones?" (commit) vs "is this per-machine local?" (gitignore).

**Naming depends on file purpose, not folder location.** Dated working notes use `<CATEGORY>-YYYY-MM-DD[-HHMM][-<slug>].md` (UPPERCASE category prefix, which may itself be multi-word with internal hyphens — e.g., `SESSION-HANDOFF`, `RECAP-CODE-DOC-ALIGNMENT`, `CONFORMANCE-NOTES`). Evergreen documentation files use lowercase-hyphen names with the `.md` extension (real examples: [`../../docs/features/mrz-parsing.md`](https://lightine.youtrack.cloud/articles/TES-A-25), [`../../docs/features/lookup-tables.md`](https://lightine.youtrack.cloud/articles/TES-A-22), [`../working-patterns.md`](../working-patterns.md)). Sequentially-numbered docs like ADRs add a 4-digit prefix to the same pattern ([`../../docs/decisions/0001-kotlin-multiplatform.md`](https://lightine.youtrack.cloud/articles/TES-A-27), [`../../docs/decisions/0007-strict-backward-compat-from-0x.md`](https://lightine.youtrack.cloud/articles/TES-A-37)). Root-level project files follow long-standing software convention (UPPERCASE: `README.md`, `CHANGELOG.md`, `LICENSE`, `SECURITY.md`, `CONTRIBUTING.md`, `CLAUDE.md`).

**Scope.** Applies to *working-pattern artifacts* the project produces — categorized notes that recur and have a documented purpose — and to project documentation. Does NOT apply to *personal scratch* (one-off debugging logs, personal todos, "thinking out loud" files) or to *externally-named folders* (`build/`, `node_modules/`, `gradle/`) that follow their tool's own conventions.

**Self-healing.** Hidden working-note folders (`.handoffs/`, `.recaps/`, `.conformance/`, etc.) materialize on first use — a session writing the first file creates the parent directory if absent. No setup script needed.

**Archiving.** Dated-working-note folders accumulate, and stale notes bury the live ones. The principle is the same for every such folder — **move what is no longer *active* into an `archive/` subfolder of the same folder, keep what is current, never delete, and never break the lookup that finds the active note** — but *what counts as "active" depends on the kind of note*:

- **Time-ordered, read-latest-first** (`.handoffs/`, `.recaps/`, and similar accumulating records like `.conformance/`) — relevance is **recency**. Keep the recent tail and archive the older ones; a rule of thumb is to archive once the folder passes ~20 files, keeping ~the most recent 10. (Applied this way, `.handoffs/` went from 50 files to 10 kept + 40 in `.handoffs/archive/`.)
- **Reference-until-done** (`.plans/`, `.reviews/`, `.discovery/`) — relevance is **status, not age**. A plan or review stays put while the work it tracks is open and is archived when that work is **completed or superseded** — which is often *not* the newest file. Do **not** archive these by recency; a count threshold is the wrong trigger here.

Two properties keep it safe and self-firing, whichever criterion applies:

- **Finding the active note still works.** Tooling globs the folder *directly* — e.g. CLAUDE.md's `ls -1 .handoffs/SESSION-HANDOFF-*.md | sort -r | head -1` for the latest handoff — and does not descend into `archive/`, so archiving never hides the current note. (When reading older history, look in `archive/` too.)
- **It matches the folder's gitignore status.** For a gitignored folder (`.handoffs/`, `.plans/`) the move is pure local housekeeping — no commit; for a committed working-note folder it is an ordinary tracked move. `archive/` inherits the parent's gitignore status.

Trigger this opportunistically — a dependency-cadence checkpoint, or any session that notices the clutter — not on a fixed schedule.

## Cross-references

- Project-level summary lives in [`../../CLAUDE.md`](../../CLAUDE.md) under "Folder and File Organization."
- Human-facing summary in [`../../docs/conventions.md`](https://lightine.youtrack.cloud/articles/TES-A-20) under "Folder and File Organization."
