---
paths:
  - "**/*.md"
  - "**/llms.txt"
  - "**/src/*Main/**/*.kt"
  - "**/Sources/**/*.swift"
---

# Documentation: where it goes, what a feature carries

## Where a piece of content goes

Ask in order. The first yes is its one home.

1. **Is it said somewhere already, or no longer true?** Nowhere. Delete it and link to
   the one copy.
2. **Must a tool or an AI session load it from disk?** (CI, Gradle, GitHub, a hook, the
   Claude harness.) Machinery: `CLAUDE.md`, `.claude/`, `.github/`, `scripts/`. A check
   beats a sentence (CLAUDE.md "Judgment principles", 7).
3. **Does it describe one public declaration?** Code docs: the KDoc or Swift doc
   comment on that declaration. The generated API reference is the Dokka javadoc jar the
   publish build makes from them; there are no per-type pages.
4. **Is it a release note, the GitHub front page, the way in for contributors, the
   vulnerability policy, or the index for AI tools?** Repo files: `CHANGELOG.md`,
   `README.md`, `CONTRIBUTING.md`, `SECURITY.md`, and `llms.txt` in the repo root.
   Nothing else is repo Markdown. Public documentation pages are not repo files.
5. **Does someone other than the maintainer need it to use or change the SDK?** The
   public shelf, which is the public YouTrack KB and never a folder in the repo, one
   kind per page:

   | The reader wants | Shelf |
   |---|---|
   | to start: what Tessera is, what works on which platform | Start here: the overview, choose your pieces, the capability matrix |
   | to see it work once, following along | Tutorial, one per platform |
   | to do one task on one platform | How-to, `how-to/<platform>/<task>` |
   | a fact the code docs cannot generate | Reference: observations, glossary, configuration, modules and artifacts, string keys |
   | to know why | Explanation |
   | to set up, build, test, or change Tessera | Contribute |

   A new consumer tutorial or how-to also gets one line in `llms.txt` (repo root): the
   link text is the page title, the link is the page's KB article URL. `llms.txt` holds
   one-line pointers and the rules for AI tools, nothing else.
6. **Otherwise** (why we chose something, how the maintainer runs a process, what
   happened): the internal KB. Decision (an ADR; shape and numbering in TES-A-17),
   Process, or History.

Work in flight (a plan, a half-finished finding) is none of these. It lives on the
issue, and the handoff points at the issue.

## How pages are written

- Page shapes, language, and the banned words:
  [Documentation style](https://lightine.youtrack.cloud/articles/TES-A-81).
- One platform per page. Kotlin pages cover JVM and Android; Swift pages cover iOS.
- Code in a page comes from a compiled sample: Kotlin in the `:samples` module, Swift
  in tessera-swift `Samples/`. The page names the sample file above the block. Until
  `:samples` exists, check every symbol against the source and list the files in the
  page's `sources:` header.
- Across homes, link by URL. The link text is the page title, never a file path.
- Pages for a feature are written with the feature and published to the public KB at
  the release tag. Until then they are drafts the team can see and consumers cannot. A
  PR that adds a feature links its draft pages for the maintainer to publish.

## What a feature PR carries

The feature checklist in [`.github/pull_request_template.md`](../../.github/pull_request_template.md).
Tick each item or say why it does not apply.

## What checks it

- Every PR (`repo · rules`): `python3 -I scripts/check-docs.py pointers` and `style`
  (no path into the retired docs folder, no dead backticked file name or CLAUDE.md
  section name, no em-dash or banned word in repo Markdown), and
  `scripts/check-markdown-links.sh`.
- Release gate: `check-docs.py code-docs` (every public declaration documented),
  `style --kdoc`, and `shelf <folder>` (every ✓ in the capability matrix has its how-to;
  the repo-root `llms.txt` lists every consumer tutorial and how-to by title). `shelf`
  reads a local folder, never the network: a fresh
  export of the public KB. It runs at the release gate only, because the pages are not in
  the repo and a PR cannot change them.
