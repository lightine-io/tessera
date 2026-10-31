# Security Policy

Tessera is a vendor-neutral SDK for reading, validating, and generating identity-document data. Because the SDK is used in trust-related contexts (border systems, document verification, identity workflows), security reports are taken seriously and handled privately.

## Supported Versions

| Version | Supported with security fixes |
|---|---|
| Latest released `0.x` minor | Yes |
| Earlier `0.x` releases | No. Upgrade to the latest minor. |

Until `1.0.0`, only the latest minor in the `0.x` line receives fixes. After `1.0.0`, a longer-support policy will be set and documented here.

## Reporting a Vulnerability

**Do not open a public issue for a security report.**

Use GitHub's private reporting: open the [repository Security tab](https://github.com/lightine-io/tessera/security), choose *Advisories*, then *Report a vulnerability*. This opens a private security advisory that only the maintainer can see.

Include in your report:

- A description of the vulnerability
- Steps to reproduce (or a minimal reproducing example)
- Affected version(s): release tag, branch, or commit SHA
- Your assessment of impact (data exposure, integrity, availability, and so on)
- Whether the issue is already public (for example disclosed in a CVE or published elsewhere) or known privately to others

## Response Timeline

These are targets, not guarantees. Tessera is a small project and timelines depend on availability.

| Stage | Target |
|---|---|
| Acknowledgement of receipt | Within 5 business days |
| Initial assessment + severity classification | Within 10 business days |
| Fix targeted in a release | Coordinated with the reporter; severity-dependent |
| Public disclosure | After fix is available, coordinated with the reporter |

## Scope

Tessera reads and checks MRZ text: parsing, generation, validation, the data model, error taxonomy, lookup tables, transliteration profiles, and the telemetry contract. The core modules do no I/O. The camera modules (`mrz-camera-*`) read camera frames and saved images in memory to locate and read the MRZ, and the Android UI module adds the scanner screens and a system photo picker. Tessera's own code holds no network code, writes no files, stores nothing, and logs nothing. One dependency does use the network: on Android, the text recognition is Google ML Kit (bundled model). It reads images on the device and sends no images or recognized text, but it uploads usage metrics to Google (device, app and performance data) shortly after it runs, may download updates, and adds the `INTERNET` permission to the app. Google documents no way to turn this off, so an app that uses the Android camera modules must declare it, for example in Google Play's Data safety section ([ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)).

An app that must make no network calls at all can remove the permission in its own manifest. In our tests text recognition kept working and the upload failed quietly, but Google does not document this, so check it again after each ML Kit update:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
</manifest>
```

This removes the permission for the whole app, so use it only if nothing else in the app needs the network. Tessera plans its own offline text recognition as the Android default after 1.0. The core modules make no network calls; on iOS, recognition uses Apple's on-device Vision framework.

Reports concerning the following are in scope:

- Logic errors that produce wrong MRZ output (security-impacting incorrectness)
- Crashes or denial-of-service on malformed input
- Information leakage through error messages or logging
- Dependencies with known vulnerabilities that materially affect the SDK
- Cryptographic concerns, once NFC chip reading ships (BAC, PACE)

Out of scope:

- Issues caused by consumer code misusing the SDK
- Issues in dependencies that do not affect the SDK's behavior
- General code-quality concerns without a security angle (open a regular issue or PR)

## Dependency Vulnerabilities

The full transitive dependency graph is submitted to GitHub on every merge to `main`, so Dependabot alerts cover everything the published artifacts pull in. Alerts are reviewed by reachability: a vulnerability in a shipped dependency is fixed, while one in a build-only or test-only dependency can be dismissed, and every dismissal carries a recorded reason. Before each release tag the maintainer lists the open alerts and triages every one (a release checklist step, not a CI job). There is no PR-time dependency gate: it was removed in 2026-10 because GitHub's snapshot-visibility lag had turned it into a silent pass. Consumers only receive released artifacts, so a dependency sitting on `main` between merge and triage never reaches them.

## Acknowledgements

The project will credit reporters in release notes for shipped fixes unless they request anonymity.
