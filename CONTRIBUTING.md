# Contributing to Tessera

Thanks for your interest. Tessera is in pre-`1.0.0` development; the public API is held to strict backward compatibility within the `0.x` line per [ADR-007](https://lightine.youtrack.cloud/articles/TES-A-37), so contribution discipline matters.

The full contributor reference lives in the project documentation. This file is a short pointer.

## Where you fit

Not sure whether a task is open to you on your machine, or which setup document to read first? See [`docs/contributor-map.md`](https://lightine.youtrack.cloud/articles/TES-A-6) — it lays out the kinds of contributor, what each can work on (the one hard constraint: iOS work needs macOS), and where each should start.

## First-time setup

If you haven't worked on this project before, start with [`docs/contributor-setup.md`](https://lightine.youtrack.cloud/articles/TES-A-12) — a per-platform walkthrough covering cloning, configuring Git identity, and setting up the SSH commit and tag signing required by branch protection on `main`. The rest of this file assumes that setup is done.

## Where the rules live

- **[`docs/workflow.md`](https://lightine.youtrack.cloud/articles/TES-A-7)** — how work flows (the board lifecycle, Definition of Ready / Done, per-type flows) and the project roles (Owner / Maintainer / Developer) — who is trusted to do what; the reasoning is in [ADR-022](https://lightine.youtrack.cloud/articles/TES-A-52)
- **[`docs/conventions.md`](https://lightine.youtrack.cloud/articles/TES-A-20)** — branch naming, PR flow, naming conventions for new types, the procedure for adding new MRZ formats, code-style commitments
- **[`.claude/git-workflow.md`](.claude/git-workflow.md)** — the end-to-end commit + push + PR workflow, including the private-content scan that runs before every push
- **[`docs/versioning.md`](https://lightine.youtrack.cloud/articles/TES-A-8)** — Semantic Versioning rules and the project's strict-backcompat-from-`0.x` stance
- **[`docs/testing.md`](https://lightine.youtrack.cloud/articles/TES-A-15)** — the testing discipline (tests alongside implementation, tests for every new public API and error type, synthetic-data-only)
- **[`docs/principles.md`](https://lightine.youtrack.cloud/articles/TES-A-5)** — the foundational principles the project is built on; please read before opening a substantial PR

## Before you open a PR

1. **Branch off `main`** with a name reflecting the type of change: `feature/...`, `fix/...`, `docs/...`, or `chore/...`
2. **Read the project's issue tracker** to see what is currently deferred and what design decisions are settled — saves rework
3. **Run the local gates:**
   - `./gradlew check` (compile, tests, Spotless, ktlint)
   - `bash scripts/check-cross-references.sh`
4. **Fill out the PR template** at [`.github/pull_request_template.md`](.github/pull_request_template.md): Summary, Documentation Impact, Tests, Open Questions Touched, Changelog, Verification
5. **Update `CHANGELOG.md`** `[Unreleased]` section per [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format. Required for non-trivial PRs

## Review process

Every PR to `main` must pass the required status checks (build, tests, changelog) before it can merge — branch protection enforces that. [`.github/CODEOWNERS`](.github/CODEOWNERS) lists the project author as owner of all paths; **required CODEOWNER approval before merge turns on as the project gains contributors** (today, with a solo author who can't approve their own PR, it isn't yet enforced — see [`docs/workflow.md`](https://lightine.youtrack.cloud/articles/TES-A-7) → "What is wired today").

The project follows **GitHub Flow**: PR for every change, no direct pushes to `main`, linear history. Squash or rebase merge — no merge commits.

## Reporting issues

- **Bug or feature request:** open a regular issue
- **Security vulnerability:** see [`SECURITY.md`](SECURITY.md) for the private disclosure process — do not open public issues for security reports

## Significant changes — discuss first

If your change touches:

- The public API of any module
- The error taxonomy structure (`MrzError`, `MrzValidationError`, `MrzWarning` hierarchies)
- An ADR or any architectural commitment in [`docs/`](https://lightine.youtrack.cloud/articles/TES-A-3)
- Cross-cutting concerns (I/O posture, telemetry, scope)

...please open an issue first to discuss. ADR-007's strict-backcompat rule means API changes are expensive; small misalignments compound. The earlier the conversation, the cheaper the change.

## Code of conduct

For now, the project follows a *"be kind, be specific, assume good faith"* convention. A formal CODE_OF_CONDUCT.md may be added if and when the contributor base grows beyond a small team.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0 (the project license per [ADR-010](https://lightine.youtrack.cloud/articles/TES-A-39)). See [`LICENSE`](LICENSE) for the full text.
