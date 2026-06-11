# Publishing Setup (Maintainer Guide)

This document is for the person who publishes Tessera artifacts to Maven Central — currently the project maintainer. It walks through the one-time setup behind publishing Tessera: a PGP signing key, Sonatype Central Portal access and a user token, and credential storage. This setup was completed for the first publish (`v0.1.1`, the publishing infrastructure committed in [ADR-016](decisions/0016-maven-coordinates-and-first-publish.md)); the guide now serves as the reference for recreating it after a credential rotation or on a new maintainer machine. **Publishing itself runs from CI** — GitHub Actions with credentials in GitHub repository secrets only, never on the maintainer's machine (section 4).

This document is living. As the publishing process evolves (rotations happen, additional registries are added), this document updates.

**Contributors do not need to do any of this.** The per-contributor machine setup (cloning, Git signing, JDK toolchain) lives in [`contributor-setup.md`](contributor-setup.md). This document is the maintainer-side counterpart — the steps a contributor explicitly does NOT need per `contributor-setup.md`'s "You do not need a Sonatype account, a GPG key for artifact signing, or any other release-time tooling" line.

---

## What needs to exist

Before the SDK can publish to Maven Central:

1. A **PGP signing key** in the maintainer's local GnuPG keyring, with its public key uploaded to a public keyserver
2. Sonatype Central Publishing Portal **account access** for the `io.lightine` namespace (already done — the namespace was claimed and verified during the [Distribution channels deferral resolution](open-questions.md), banked in PR [#75](https://github.com/lightine-io/tessera/pull/75) and locked under [ADR-016](decisions/0016-maven-coordinates-and-first-publish.md))
3. A Sonatype Central Portal **user token** (generated; distinct from the account login password)
4. **Credentials stored** where the release workflow can read them — GitHub Actions secrets in the `release` environment; never on the maintainer's machine

Steps below cover each in order. All four are in place — signing and Sonatype Central Portal publishing are wired; the first publish shipped maintainer-side at `v0.1.1`, and publishing moved to CI for the `0.2.x` line (`v0.2.1`, 2026-06-07, with credentials rotated to the CI-only posture beforehand). The steps are the reference for recreating this setup after a key or token rotation.

---

## 1. PGP signing key

### Why this is separate from the SSH signing key

The project already uses an SSH signing key for git commits (set up per [`contributor-setup.md`](contributor-setup.md) section 3 and described in [`.claude/git-workflow.md`](../.claude/git-workflow.md)). Maven Central requires a **PGP/GnuPG** key for signing published artifacts: different algorithm family (RSA/ECC under the OpenPGP standard), different toolchain (`gpg`), different file format, different keyserver infrastructure. The two have nothing to do with each other.

Do not try to convert or reuse the SSH key. Generate the PGP key fresh.

### Install GnuPG

**macOS:**

```bash
brew install gnupg
gpg --version  # confirm 2.4.x or newer
```

**Linux (Ubuntu/Debian):** GnuPG is usually preinstalled. Check `gpg --version`; install via `sudo apt install gnupg` if missing.

**Windows:** Install [Gpg4win](https://gpg4win.org/) (bundles GnuPG plus a GUI).

The macOS path is the project-verified one as of 2026-05. Linux and Windows follow the same `gpg` CLI verbs; the package-management commands differ.

### Generate the key

```bash
gpg --full-generate-key
```

When prompted:

- **Key type:** `(1) RSA and RSA` (default — primary key for signing, subkey for encryption)
- **Key size:** `4096` (current recommendation; `3072` is also fine, and is the GnuPG default)
- **Expiry:** Choose based on preference:
  - `0` (no expiry) — simplest; same key forever
  - `2y` (2 years) — best practice; renew before expiry. Past artifacts stay verifiable even after the key expires; only new signing requires extension or rotation
  - Recommendation: `2y` with a calendar reminder to renew at the 22-month mark
- **Real name:** `Asker Asadov` (matches the POM `<developer><name>` field)
- **Email:** `asker.asadov@gmail.com` (matches the POM `<developer><email>` field)
- **Comment:** `Maven Central signing` (optional; helps you identify the key in `gpg --list-keys` output later — leave blank if you prefer a UID without a comment)
- **Passphrase:** Strong. Save in your password manager. Required every time you sign a release (Gradle can cache for a daemon session if you configure `gpg-agent`)

### Find your key ID

```bash
gpg --list-secret-keys --keyid-format LONG
```

Output looks like:

```
sec   rsa4096/ABCD1234EF567890 2026-05-27 [SC]
      0123456789ABCDEF0123456789ABCDEF01234567
uid                 [ultimate] Asker Asadov (Maven Central signing) <asker.asadov@gmail.com>
ssb   rsa4096/0987654321FEDCBA 2026-05-27 [E]
```

The 16-character ID after `rsa4096/` on the `sec` line is your **long key ID** (`ABCD1234EF567890` in the example above); the full fingerprint on the next line is what keyservers and Maven Central look up. The `<KEY_ID>` placeholder in the `gpg` commands throughout this document accepts the long ID, the short ID, or the full fingerprint interchangeably.

For the `signingInMemoryKeyId` Gradle property in step 4, however, you need the **short key ID** specifically — the last 8 characters of the long ID (`EF567890` in the example above). The Gradle signing plugin validates that field as an 8-hex-digit value and rejects the 16-character long form (`The key ID must be in a valid form (eg 00B5050F or 0x00B5050F)`). Print the short form directly with:

```bash
gpg --list-secret-keys --keyid-format SHORT
```

### Back up the secret key

The key only exists on this machine. If you lose it, you lose the ability to sign **new** releases. Already-published artifacts on Maven Central stay verifiable forever (Sonatype caches the public key); the loss only affects future signing.

Export to an ASCII-armored file:

```bash
gpg --export-secret-keys --armor <KEY_ID> > tessera-signing-key.asc
```

Store the resulting `.asc` file encrypted — examples:

- 1Password "secure note" attachment
- Encrypted USB stick in a physical safe
- A second machine with disk encryption that is rarely online

Then delete the unencrypted file from the maintainer machine:

```bash
rm tessera-signing-key.asc
```

---

## 2. Upload the public key to a keyserver

Maven Central validates signed artifacts by fetching the signer's public key from a keyserver. The signature in the `.asc` file references your key fingerprint; Maven Central looks the fingerprint up at publish time.

**Recommended keyserver:** [`keys.openpgp.org`](https://keys.openpgp.org/about) — verified-email model; only keys whose email has been confirmed are searchable by email. Other keyservers commonly used (no email verification required): `keyserver.ubuntu.com`, `pgp.mit.edu`.

Send the public key:

```bash
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
```

Then check the email account you used for the key (`asker.asadov@gmail.com` per step 1). `keys.openpgp.org` sends a verification link. Click it. The key becomes searchable by email once verified.

**Verify the upload from a different terminal session** (or, ideally, a different machine):

```bash
gpg --keyserver keys.openpgp.org --recv-keys <KEY_ID>
```

Should retrieve the public key cleanly. If it returns "key not found," wait a few minutes for propagation and try again.

For redundancy, you can `--send-keys` to multiple servers — Maven Central's validation can fetch from any of them. One server is enough for first publish.

---

## 3. Sonatype Central Portal user token

The Sonatype Central Publishing Portal (the post-2024 endpoint, replacing the legacy OSSRH that was deprecated in mid-2024) authenticates publish requests with **user tokens**, not account passwords. Generate one:

1. Go to [https://central.sonatype.com](https://central.sonatype.com) and sign in with the account that owns the `io.lightine` namespace (verified earlier; the namespace verification step is the one done before [ADR-016](decisions/0016-maven-coordinates-and-first-publish.md) was written)
2. Top-right account menu → "View Account" → "Generate User Token"
3. Sonatype shows the token's **username** and **password** **once** — copy both immediately into your password manager. They cannot be retrieved later; if lost, generate a new token (which invalidates the previous one)

The token pair (username + password) is what Gradle uses to authenticate publish requests. It is not the same as your Sonatype account login.

---

## 4. Store credentials where the release workflow can read them

Vanniktech's `gradle-maven-publish-plugin` (wired up in PR [#80](https://github.com/lightine-io/tessera/pull/80)) reads five credential values:

| What | Gradle property | Env var (cross-platform) |
|---|---|---|
| Signing key (ASCII-armored) | `signingInMemoryKey` | `ORG_GRADLE_PROJECT_signingInMemoryKey` |
| Signing key ID (short, 8-char) | `signingInMemoryKeyId` | `ORG_GRADLE_PROJECT_signingInMemoryKeyId` |
| Signing key passphrase | `signingInMemoryKeyPassword` | `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` |
| Sonatype token username | `mavenCentralUsername` | `ORG_GRADLE_PROJECT_mavenCentralUsername` |
| Sonatype token password | `mavenCentralPassword` | `ORG_GRADLE_PROJECT_mavenCentralPassword` |

**The current path — decided and in force since the `0.2.x` releases: the five values live in GitHub Actions secrets only, and publishing runs from CI. Credentials never live on the maintainer's machine.** (The local `~/.gradle/gradle.properties` used for the first publish was deleted at the pre-`0.2.0` cleanup, 2026-06-07.)

### GitHub Actions secrets (the one supported path)

| Secret (release environment) | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Sonatype token username (step 3) |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype token password (step 3) |
| `SIGNING_KEY` | ASCII-armored secret key — multi-line preserved; GitHub secrets and env vars handle it natively, no `\n` escaping |
| `SIGNING_KEY_ID` | Signing key ID — the **short 8-char** form (step 1); the plugin rejects the 16-char long form |
| `SIGNING_KEY_PASSWORD` | Signing key passphrase |

The release workflow ([`.github/workflows/release.yml`](../.github/workflows/release.yml)) maps these secrets to the `ORG_GRADLE_PROJECT_*` env vars in the table above before invoking Gradle. It runs on a `v*` tag, pauses for `release`-environment approval — the secrets are scoped to that environment, so nothing is exposed before the approval — stages the deployment to the Central Portal (manual release on the Portal UI, not auto-published), and builds the iOS XCFramework + GitHub Release. A `release · dry-run` job is available via `workflow_dispatch`.

To set or rotate a secret: repository Settings → Environments → `release` → secrets, or `gh secret set <NAME> --env release`. Export the key for the `SIGNING_KEY` value with `gpg --export-secret-keys --armor <KEY_ID>` and paste it whole — do not write it to a file on disk.

### Historical: local credential storage (retired — do not recreate)

The first publish (`v0.1.1`) ran maintainer-side with these five values in user-level `~/.gradle/gradle.properties` (Gradle properties cannot hold multi-line values, so the key needed literal `\n`-joining — a constraint that does not exist in the CI path). That option is **retired by decision**: credentials never live on the laptop. If a local file holding any of these values reappears, treat it as an incident — delete it and rotate the affected credentials (sections 1 and 3). Do not read credential-bearing local files into tools or session transcripts either; that is how a prior exposure happened.

---

## 5. Verification

These are sanity checks the maintainer can run after setup, or after a key/token rotation:

```bash
# Confirm the key is in the local keyring
gpg --list-secret-keys --keyid-format LONG
# Should show the rsa4096 sec key with the expected name + email + comment

# Confirm the public key is on the keyserver
gpg --keyserver keys.openpgp.org --recv-keys <KEY_ID>
# Should retrieve cleanly, not "key not found"

# Pre-tag structural check (run before ANY release tag): executes the
# publication-metadata tasks that `build` and `publishToMavenCentral --dry-run`
# skip — the 0.2.1 lesson (an empty-module publication failure only surfaced
# at the authenticated tag publish until this check was adopted)
./gradlew publishToMavenLocal --console=plain
```

The maintainer machine holds no publishing credentials, so the local check verifies publication *structure*, not the signed upload. The signed path is verified in CI: the `release · dry-run` job (`workflow_dispatch`), and the staged, environment-gated publish on the release tag itself. On a machine that does hold signing credentials (CI), the publish also produces `.asc` signature files alongside each artifact; `gpg --verify <artifact>.asc` should report a good signature from the maintainer key.

---

## iOS distribution (Swift Package Manager)

This guide covers **Maven Central** (JVM + Android). The iOS modules (`mrz-camera-*`) are **not** published to Maven Central — iOS distributes through **Swift Package Manager**, a different channel with no PGP/Sonatype credentials ([ADR-019](decisions/0019-ios-distribution-via-spm.md)). The release-time steps were executed for the first time at `v0.2.1` (2026-06-07); the runbook below records the verified flow.

### SPM release runbook

**Verified working: 2026-06-07 (`v0.2.1`).**

Prerequisites: the release workflow ran on the `v*` tag — it builds `Tessera.xcframework.zip` (`./gradlew :mrz-camera-ios:packTesseraXCFramework`), attaches it to the main repo's GitHub Release, and prints the zip's SHA-256 into the release body (the second authenticity channel — see below). The `lightine-io/tessera-swift` distribution repo exists (created at `v0.2.1`) and is protected: PR-required, signed commits, immutable `v*` tags, no bypass — and signing there is configured per-clone.

1. Take the checksum from the GitHub Release body — or recompute it independently: download the asset and run `swift package compute-checksum Tessera.xcframework.zip`. The two must match.
2. In `tessera-swift`, update `Package.swift`: point the `binaryTarget(name: "Tessera", url: …, checksum: …)` at this tag's release-asset URL with the verified checksum. The manifest needs `swift-tools-version:6.0` — `.iOS(.v18)` is rejected under 5.9 (`swift package dump-package` catches it).
3. Verify locally **before** tagging: `swift package compute-checksum` matches, and `swift package dump-package` parses.
4. Open a PR in `tessera-swift`, merge, then create the **signed** `vX.Y.Z` tag matching the main repo's release tag. Tags there are immutable — verify before tagging, not after.
5. Done — `import Tessera` resolves for SPM consumers pinned to the tag.

**Artifact authenticity (decided at `v0.2.1`):** the zip's SHA-256 is published in the main repo's GitHub Release body as an independent second channel — an attacker would have to compromise both repos to swap the binary unnoticed. Apple Developer ID codesigning of the framework slices stays deferred; revisit if the consumer audience grows (ADR-019).

Automating this cross-repo flow is tracked in [`open-questions.md`](open-questions.md) ("Automate the cross-repo SPM (iOS) publish").

---

## Cross-references

- [`decisions/0016-maven-coordinates-and-first-publish.md`](decisions/0016-maven-coordinates-and-first-publish.md) — coordinate shape, lockstep versioning, BOM, first-publish version and scope; the umbrella decision this setup serves
- [`contributor-setup.md`](contributor-setup.md) — per-contributor machine setup (SSH signing key, JDK toolchain, IDE config); the contributor-side counterpart to this document
- [`conventions.md`](conventions.md) — module naming, package conventions, including the "Module Boundaries" rule that constrains what each published artifact contains
- The publishing infrastructure slices (all shipped): PR [#80](https://github.com/lightine-io/tessera/pull/80) (vanniktech plugin + POM metadata + lockstep version), PR [#81](https://github.com/lightine-io/tessera/pull/81) (Dokka 2 for javadoc jars), PR [#82](https://github.com/lightine-io/tessera/pull/82) (`tessera-bom`), PR [#88](https://github.com/lightine-io/tessera/pull/88) (PGP signing), PR [#89](https://github.com/lightine-io/tessera/pull/89) (Sonatype Central Portal + first publish), PR [#90](https://github.com/lightine-io/tessera/pull/90) (`v0.1.1` release)
