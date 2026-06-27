# Security Policy

Tessera is a vendor-neutral SDK for reading, validating, and generating identity-document data. Because the SDK is used in trust-related contexts (border systems, document verification, identity workflows), security reports are taken seriously and handled privately.

## Supported Versions

| Version | Supported with security fixes |
|---|---|
| Latest released `0.x` minor | ✅ |
| Earlier `0.x` releases | ❌ — upgrade to the latest minor |

Until `1.0.0` (the public-stability and open-source commitment per [ADR-011](https://lightine.youtrack.cloud/articles/TES-A-41)), only the latest minor in the `0.x` line receives backports. After `1.0.0`, a longer-support policy will be established and documented here.

## Reporting a Vulnerability

**Please do not open public issues for security reports.**

Use one of the following private channels:

1. **Preferred: GitHub's "Report a vulnerability" feature.** Go to the [repository Security tab](https://github.com/lightine-io/tessera/security) → *Advisories* → *Report a vulnerability*. This opens a private security advisory visible only to the project maintainer.
2. **Alternative: email** asker.asadov@gmail.com with `[Tessera Security]` in the subject line.

Include in your report:

- A description of the vulnerability
- Steps to reproduce (or a minimal reproducing example)
- Affected version(s) — release tag, branch, or commit SHA
- Your assessment of impact (data exposure, integrity, availability, etc.)
- Whether the issue is already public (e.g., disclosed in a CVE, published elsewhere) or known privately to others

## Response Timeline

These are targets, not guarantees. Tessera is a small project and timelines depend on availability.

| Stage | Target |
|---|---|
| Acknowledgement of receipt | Within 5 business days |
| Initial assessment + severity classification | Within 10 business days |
| Fix targeted in a release | Coordinated with the reporter; severity-dependent |
| Public disclosure | After fix is available, coordinated with the reporter |

## Scope

Tessera is a pure-logic SDK for MRZ (parsing, generation, validation), the data model, error taxonomy, lookup tables, transliteration profiles, and the telemetry contract. Its core does no I/O. As of `0.2.0`, the optional camera-reading modules (`mrz-camera-*`) perform **camera input only** — reading frames in memory to locate and OCR the MRZ, releasing them promptly — and still make no network calls, write no files, and store nothing. The SDK never phones home.

Reports concerning the following are in scope:

- Logic errors that produce wrong MRZ output (security-impacting incorrectness)
- Crashes or denial-of-service on malformed input
- Information leakage through error messages or logging
- Dependencies with known vulnerabilities that materially affect the SDK
- Cryptographic concerns (when chip verification ships in `0.6.0`)

Out of scope:

- Issues caused by consumer code misusing the SDK
- Issues in dependencies that do not affect the SDK's behavior
- General code-quality concerns without a security angle (please open a regular issue or PR)

## Acknowledgements

The project will credit reporters in release notes for shipped fixes unless they request anonymity.
