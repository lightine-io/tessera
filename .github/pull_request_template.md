## What and why

<!-- One to three sentences. Link the issue: TES-n or #n. -->

## Tests

<!-- What you added or changed. "None" needs a reason. -->

## Changelog

- [ ] `CHANGELOG.md` entry under `[Unreleased]`, or the `no-changelog` label because nothing ships (say why here). CI checks for one or the other; the label also moves the board issue to Done at merge. The entry explains the change in plain words; no issue ids or private links (the issue id stays in the PR title and commit).

## Feature documentation

<!-- Delete this section when the PR adds or changes no public API or capability.
     Public pages live in the public KB, not in this repo: link the draft pages here and
     the maintainer publishes them at the release. Where each piece goes:
     .claude/rules/documentation.md. Not applicable? Say why. -->

- [ ] Capability matrix (public KB page): row added or updated
- [ ] One how-to page (public KB) per platform marked ✓ in that row
- [ ] Code docs on every new public declaration
- [ ] Every code block on those pages: each symbol checked against the source, and the files listed in the page's `sources:` header (until the `:samples` module exists)
- [ ] `llms.txt` (repo root): one line per new tutorial or how-to page; link text is the page title, link is its KB article URL
- [ ] Explanation page (public KB), if this PR made a decision a user should understand (plus an ADR in the internal KB if it binds future work)
- [ ] CHANGELOG line (above)
- [ ] API reference: nothing by hand; it is the Dokka javadoc jar that `publish · smoke` builds from the code docs (there are no per-type pages)
