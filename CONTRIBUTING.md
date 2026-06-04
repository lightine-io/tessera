# Contributing to Tessera

Thanks for your interest. Tessera is in pre-`1.0.0` development; the public API is held to strict backward compatibility within the `0.x` line per [ADR-007](docs/decisions/0007-strict-backward-compat-from-0x.md), so contribution discipline matters.

The full contributor reference lives in the project documentation. This file is a short pointer.

## Where you fit

Not sure whether a task is open to you on your machine, or which setup document to read first? See [`docs/contributor-map.md`](docs/contributor-map.md) — it lays out the kinds of contributor, what each can work on (the one hard constraint: iOS work needs macOS), and where each should start.

## First-time setup

If you haven't worked on this project before, start with [`docs/contributor-setup.md`](docs/contributor-setup.md) — a per-platform walkthrough covering cloning, configuring Git identity, and setting up the SSH commit and tag signing required by branch protection on `main`. The rest of this file assumes that setup is done.

## Where the rules live

- **[`docs/conventions.md`](docs/conventions.md)** — branch naming, PR flow, naming conventions for new types, the procedure for adding new MRZ formats, code-style commitments
- **[`.claude/git-workflow.md`](.claude/git-workflow.md)** — the end-to-end commit + push + PR workflow, including the private-content scan that runs before every push
- **[`docs/versioning.md`](docs/versioning.md)** — Semantic Versioning rules and the project's strict-backcompat-from-`0.x` stance
- **[`docs/testing.md`](docs/testing.md)** — the testing discipline (tests alongside implementation, tests for every new public API and error type, synthetic-data-only)
- **[`docs/principles.md`](docs/principles.md)** — the foundational principles the project is built on; please read before opening a substantial PR

## Before you open a PR

1. **Branch off `main`** with a name reflecting the type of change: `feature/...`, `fix/...`, `docs/...`, or `chore/...`
2. **Read [`docs/open-questions.md`](docs/open-questions.md)** to see what is currently deferred and what design decisions are settled — saves rework
3. **Run the local gates:**
   - `./gradlew check` (compile, tests, Spotless, ktlint)
   - `bash scripts/check-cross-references.sh`
4. **Fill out the PR template** at [`.github/pull_request_template.md`](.github/pull_request_template.md): Summary, Documentation Impact, Tests, Open Questions Touched, Changelog, Verification
5. **Update `CHANGELOG.md`** `[Unreleased]` section per [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format. Required for non-trivial PRs

## Review process

Every PR to `main` requires approval from a CODEOWNER (currently only the project author, listed in [`.github/CODEOWNERS`](.github/CODEOWNERS)). Branch protection rules enforce this.

The project follows **GitHub Flow**: PR for every change, no direct pushes to `main`, linear history. Squash or rebase merge — no merge commits.

## Reporting issues

- **Bug or feature request:** open a regular issue
- **Security vulnerability:** see [`SECURITY.md`](SECURITY.md) for the private disclosure process — do not open public issues for security reports

## Significant changes — discuss first

If your change touches:

- The public API of any module
- The error taxonomy structure (`MrzError`, `MrzValidationError`, `MrzWarning` hierarchies)
- An ADR or any architectural commitment in [`docs/`](docs/)
- Cross-cutting concerns (I/O posture, telemetry, scope)

...please open an issue first to discuss. ADR-007's strict-backcompat rule means API changes are expensive; small misalignments compound. The earlier the conversation, the cheaper the change.

## Code of conduct

For now, the project follows a *"be kind, be specific, assume good faith"* convention. A formal CODE_OF_CONDUCT.md may be added if and when the contributor base grows beyond a small team.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0 (the project license per [ADR-010](docs/decisions/0010-apache-2-license.md)). See [`LICENSE`](LICENSE) for the full text.
