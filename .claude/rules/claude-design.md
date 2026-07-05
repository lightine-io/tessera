---
paths:
  - ".design/**"
  - "**/mrz-camera-ui-android/**"
---

# Claude Design — the default-UI design home

**The visual design of the SDK's default UI lives in a claude.ai Design project, not in git.** The
canonical source of truth is the Claude Design (design-system) project **"Tessera Default UI"**. The
git repo carries only *local working copies* of the mockups, under `.design/` — **gitignored**, i.e. a
*local* home in the sense of [`content-placement.md`](content-placement.md), with the published copy in
Claude Design (an external design home that sits alongside git/KB, not inside them). Do not treat
`.design/` as authoritative or commit it; do not add a "valid"-verdict or other oracle affordance to a
mockup (the reader-not-oracle principle applies to the UI too).

## The working copies (`.design/default-ui/`)

- One self-contained HTML file per scanner state (state map + the screen states). **Self-contained is
  mandatory** — no external `src`/`href`/`<link>`/`<script>`/`url()`; the Design pane renders each file
  standalone.
- **Line 1 of every file is a `<!-- @dsCard group="…" -->` marker.** That marker is what registers the
  file as a card in the Design System pane (compiled into `_ds_manifest.json` on sync). A new mockup
  without a line-1 `@dsCard` will not appear as a card. Keep the `group` values consistent (e.g.
  `Overview`, `Scanner screens`).

## Syncing (local ⇄ Claude Design)

- **Mechanism:** the `/design-sync` skill driving the `DesignSync` tool (`list_projects` / `create_project`
  → `finalize_plan` → `write_files`). One component at a time, never a wholesale replace.
- **Requires an INTERACTIVE `claude` terminal.** The first `DesignSync` call triggers `/design-login`,
  which needs an interactive terminal. **Headless / agent / cron sessions CANNOT authorize it** — the call
  fails with "needs design-system authorization … not available in this environment." In that case
  **surface it to the maintainer; do not fake, skip silently, or claim it synced.** (Same discipline as a
  missing prescribed driver — see [`mobile-dev-workflow.md`](mobile-dev-workflow.md).)
- Typical run: `cd .design/default-ui` → `/design-sync` → authorize → it creates/updates "Tessera Default
  UI" and writes the `@dsCard` files.

## Keep-in-sync duty (why this rule also loads on the UI module)

When you change the **default-UI screens** in `mrz-camera-ui-android` (e.g. `MrzScannerScreen.kt` — copy,
a new state, an error branch), the `.design/` mockups and the Claude Design project drift from the code.
**Update the matching mockup and re-sync** (or, if you're in a session that can't authorize, flag the sync
as a follow-up in the handoff). The design and the implementation are meant to describe the same UI.

## Current state pointer

Whether the "Tessera Default UI" project has actually been created/synced yet is *state*, not rule — it
lives in the auto-memory (`project_claude_design_default_ui`), because an un-synced project means `.design/`
is the only copy and carries a durability risk worth flagging every session.
