# Testing

This document defines the testing discipline for the project: what kinds of tests exist, what each is for, what we commit to testing, and what we do not. Where principles describe *what we value* and architecture describes *how the code is organized*, this document describes *how we verify the code does what we say it does*.

This document is living. Testing practices evolve as the project encounters new situations. New testing conventions can be added; existing ones can be revised.

---

## Categories of Tests

The project uses several distinct categories of tests, each serving a specific purpose.

### Unit Tests

Tests for pure logic in core modules: parsers, generators, validators, lookup tables, transliteration profiles. These tests:

- Run in `commonMain` test sources, executable on every target the SDK supports
- Have no dependencies on platform APIs, I/O, or external services
- Run fast — milliseconds per test, allowing thousands of tests in a single suite
- Are deterministic: the same input always produces the same output

Unit tests are the foundation of the project's testing strategy. Most of the SDK's logic is testable as pure functions, and unit tests are the primary way that correctness is established.

### Property-Based Tests

Tests that verify invariants over generated inputs rather than specific examples. The most important application is the round-trip property:

> `parse(generate(x))` equals `x` at the raw-field level for any valid `x`

A property-based test generates random valid inputs, runs them through the round trip, and asserts equality. Any divergence indicates a bug in either the parser or the generator. This single property catches a huge range of potential errors.

Other properties worth testing this way:

- Validation determinism: validating the same data twice produces the same result
- Format identification consistency: any MRZ produced by `generateTDx` is identified as `TDx` by the parser
- Idempotence of normalization: applying the same normalization twice produces the same output as applying it once

Property-based tests live alongside unit tests in `commonMain` test sources.

### Security and Adversarial Testing

Tests that probe the SDK's behavior under deliberately hostile or unexpected inputs. The category exists because the SDK is a reader of untrusted data: every input the parser receives could be the result of OCR error, accidental corruption, or active manipulation. The parser must behave predictably and safely under all of them.

Security and adversarial testing covers:

- **Input fuzzing** — random byte sequences fed to the parser. The parser must never crash, hang, or produce undefined behavior on any input. Validation failures are acceptable; crashes are not.
- **Boundary inputs** — inputs at the edges of what the format defines: zero-length fields, fields filled entirely with the filler character, dates at the year boundary, document numbers at the maximum allowed length, names that exactly fill the truncation indicator threshold.
- **Adversarial fixtures for reader-not-oracle commitments** — inputs deliberately crafted to test that the SDK never silently corrects, infers, or reinterprets data. A field with a misspelling stays misspelled; a field with an unrecognized country code is exposed verbatim; a field that fails check digit validation still has its data returned.
- **Unicode and character set edge cases** — inputs containing characters outside the MRZ alphabet (lowercase, accented, non-Latin scripts, control characters, zero-width characters). The parser must reject these as character set violations rather than handling them inconsistently.
- **Resource exhaustion probes** — inputs designed to trigger excessive memory use, infinite loops, or unbounded recursion if the parser has those vulnerabilities.

These checks are integrated across the parser, format, and generator test suites rather than living in separate dedicated security-test files — for example, character-set violations in `MrzAlphabetTest.kt`, and boundary and adversarial cases throughout the `MrzParser*`, `MrzValidator*`, and `MrzGenerator*` tests. They run as part of the regular suite, not a separate "security pass" — defensive behavior is part of correctness. (Consolidating them into a dedicated fuzz/adversarial suite, and filling gaps such as systematic input fuzzing, is a candidate future improvement.)

The pre-public-release security review pass (tracked in `docs/open-questions.md`) is a separate, larger activity that examines areas the regular test suite cannot fully cover (side channels, dependency integrity, etc.).

### Integration Tests

Tests for code that crosses module boundaries, especially when platform I/O is involved. Examples:

- A camera frame flows from the platform-specific camera module through the OCR layer and into the MRZ parser
- An NFC tag is read, BAC/PACE protocols execute, data groups are extracted, and the result is parsed
- A full read flow exercises the camera or NFC module end-to-end

Integration tests live in platform-specific test sources (`androidHostTest`, `iosTest`, etc.) because they require platform APIs. They are slower than unit tests and are run less frequently — typically per-commit on CI rather than on every save during development.

### Platform-Specific Tests

Tests that verify behavior unique to a specific platform. Examples:

- Android `Activity` and `Fragment` integration with the SDK's UI module
- iOS `UIViewController` lifecycle compatibility
- Platform OCR engine quirks that need workarounds

These tests live in their respective platform test sources. They cover the gaps that pure unit tests cannot reach.

---

## What We Commit to Testing

The following are committed coverage targets. Implementations that do not meet these are not ready for release.

### Every public API

Every public method, property, and type has at least one test that exercises it with a representative input. This is not coverage by line count; it is behavioral coverage. A consumer reading the API documentation should be able to find a test that demonstrates each documented behavior.

### Every error path

Every error type has at least one test that produces it. The test names the type and verifies that the resulting value matches the documentation. If a new error type is added without a test, the test must be added before the type is shipped.

### Every supported MRZ format

Every supported format (TD1, TD2, TD3, MRV-A, MRV-B) has tests covering:

- A valid example (round-trip parse → generate → parse with raw-field equality)
- An invalid structural example (wrong line count, wrong line length, character set violation)
- A valid structure with check digit failures (verifies that validation failures are reported, not errors)
- A valid structure with semantic anomalies (verifies that warnings are produced where expected)

The valid example for each format comes from the project's own MRZ generator, not from real documents.

### The round-trip property

For every supported format, a property-based test verifies that `parse(generate(x))` equals `x` at the raw-field level. This is the single most valuable test in the project — a small amount of code that verifies a deep correctness property across the whole parser/generator system.

### Every shipped transliteration profile

Every transliteration profile shipped with the SDK has tests covering:

- A representative input that exercises the profile's distinctive rules
- An input that produces the same output as the ICAO default profile (sanity check)
- An input with characters not covered by the profile (verifies the profile's fallback policy)

### Validation logic

Every validator in the validation layer has its own test. A check digit validator has tests for matching digits (passes), mismatching digits (validation failure with correct context), and edge cases (empty fields, filler characters). A semantic validator has tests for canonical valid input, structurally-valid-but-anomalous input, and edge cases relevant to its specific check.

---

## What We Do Not Commit to Testing

These are explicit non-commitments. They keep the testing scope bounded to what is genuinely valuable.

### Coverage percentage

The project does not commit to a numeric coverage percentage. Coverage tooling can run, and reports can inform development, but coverage is not a gate. Coverage by line count is a poor proxy for behavioral coverage; high coverage with weak tests is worse than moderate coverage with strong tests.

What the project commits to is *behavioral coverage*: every documented behavior has a test, every error type has a test, every public API has a test. Whether that produces 80% or 95% coverage by line count is not the metric.

### Real document data

No tests use real document data. All test fixtures are synthetic, generated by the SDK's own MRZ generator. ICAO-published test vectors are used where applicable. This is a privacy and legal commitment, not a testing convenience choice. The symmetric parser/generator design means any test case can be generated; there is no excuse to use real data.

If real document data appears in a test, it is a bug to fix, regardless of whether the data is anonymized.

### Network behavior

The SDK has no network calls. There is no network behavior to test. If a future release adds a feature with network behavior (which would itself be a significant scope decision), tests for that feature would be added at that time.

### Platform UI rendering details

The SDK's optional UI modules use native UI frameworks. Testing that a Compose `Button` renders correctly is the responsibility of Jetpack Compose, not this SDK. The project tests the SDK's behavior (does it pass the right state to the UI, does it react to UI events correctly) but not the UI framework's behavior.

### Platform OCR or NFC engine behavior

The SDK uses platform OCR and NFC engines. Testing that ML Kit recognizes characters correctly is the responsibility of ML Kit. The project tests the SDK's integration with these engines — error handling, callback patterns, edge cases — but not the engines themselves.

### Performance benchmarks

The project does not commit to performance benchmarks as part of the test suite. Performance is monitored separately when relevant. Adding performance tests to the regular suite slows test runs and creates flaky tests on slower CI machines.

If performance matters for a specific feature (e.g., parsing latency for live camera reading), targeted performance tests can be added for that feature, marked clearly, and run on consistent hardware.

---

## Test Fixture Discipline

### Synthetic generation

All MRZ fixtures used in tests are generated by the project's own `MrzGenerator`. The generation is deterministic given the input parameters, so fixtures are reproducible. Tests can either:

- Generate fixtures inline within the test (clear, but verbose)
- Use shared generation helpers for common patterns (cleaner, but requires reading the helper to understand the test)

Both patterns are acceptable. The choice is per-test based on readability.

### ICAO test vectors

ICAO Doc 9303 includes example MRZs in some sections. These are publicly published test vectors and can be used in tests. Tests using ICAO test vectors should reference the specific Section that defines them.

### Edge case generation

The synthetic generation approach means edge cases can be deliberately constructed: a TD3 with a very long document number that triggers the long-doc-number extension, a name field at exactly the truncation boundary, a date at a century boundary that triggers the inference heuristic. The generator produces these on demand.

### No data harvesting

The project never harvests real MRZ data from any source for use in tests. This includes:

- Photos found online
- Documents from contributors' personal documents
- Anonymized data from real document populations
- Data from any government-provided test sets that are not publicly published

The synthetic generation approach is sufficient for all testing needs.

---

## Camera Reading Testing

Live camera reading (release 0.2.0) is the first feature with a real platform-I/O surface, so its testing follows the Integration-test and Platform-specific categories above, in three layers:

- **Host (JVM), no device** — the *glue logic* we own (frame → MRZ-region crop → call OCR → map candidate string → `mrz-core` parse, plus the `Camera…` error paths) is tested with **injected frames and a mock/stub OCR engine**. This is the bulk of the coverage and runs on CI on every commit, with no device or emulator. The analyse-frame core's seam (`MrzTextRecognizer`) makes this possible: the OCR engine is injected, so the core is exercised host-side with a mock. The platform recognizer that can't be host-tested (Android's `MlKitMrzTextRecognizer`) is instead **compile-checked** by the `android-compile` CI job, and its real OCR is verified at the emulator/device layers below. The **owns-the-camera-session scanners** (`CameraXMrzScanner` on Android, `AVCaptureMrzScanner` on iOS) likewise cannot be host- or simulator-tested — they drive a live camera session — so the streaming contract they build on (`MrzCameraScanner` / the `scan` engine) is host-tested in `mrz-camera-core`, the scanners themselves are compile-checked on CI, and their live behaviour (frame streaming, frame-buffer hygiene — Android's `ImageProxy.close()`, iOS's `CMSampleBuffer` `CFRetain`/`CFRelease` accounting — and the asynchronous capture-error mapping) is verified at the device layer. One slice of that is pulled below the device layer where it can be: the iOS scanner's capture-failure → `CameraError` mapping is **unit-tested on the Simulator** (`AVCaptureMrzScannerErrorMappingTest`), exercising the pure mapping directly because its live triggers (a runtime fault, an in-use interruption, a denied permission) can't be reproduced there — and the runtime-fault trigger can't be reproduced on a real device at all (it is a genuine media-services/hardware fault, per `docs/open-questions.md`).
- **Emulator / Simulator — real OCR on a still image** — a synthetic MRZ rendered to an image is fed to the real platform OCR engine (ML Kit / Apple Vision) to confirm the wiring end-to-end without a camera. The iOS Simulator has no camera but runs Apple Vision on a supplied image. These are instrumented / platform tests.
- **Physical device — live lens** — the only place a real camera stream is validated end-to-end; run locally/manually (the Android emulator's camera is synthetic; the iOS Simulator has none). Not a CI gate.

What we commit to testing here is **our** code: region detection/cropping, frame-to-result mapping, parsing-mode behavior (strict default + lenient), quality-signal exposure, and every `Camera…` error path. What we do **not** test is the OCR engine itself (ML Kit / Apple Vision) — already an explicit non-commitment above; we test our *integration* with it (error handling, callback/Flow patterns, edge cases), not its recognition accuracy. The injected-frame and still-image layers cover everything that is ours to verify.

Synthetic fixtures still apply: camera test inputs are generated MRZs (rendered to images where an image is needed), never real document captures.

## Forward-Looking Note: Cryptographic Protocol Testing

When NFC chip reading is implemented (release 0.6.0 target), the testing scope expands significantly. Cryptographic protocols (BAC, PACE) have specific testing concerns that are not covered by the categories above:

- **Protocol conformance tests** — verifying that the SDK's BAC and PACE implementations match the ICAO Doc 9303 Part 11 specifications byte-for-byte
- **Replay resistance tests** — verifying that captured protocol exchanges cannot be reused
- **Nonce handling tests** — verifying that random values are correctly generated and that protocol failures occur when nonces are reused or predictable
- **Key derivation tests** — verifying that keys derived from MRZ data match the expected values for known test vectors
- **Negative tests** — verifying that the SDK rejects malformed protocol responses, invalid certificates, and tampered data groups

When NFC work begins, this testing document should be updated to add a dedicated "Cryptographic Protocol Testing" section with the specific test categories, fixtures, and commitments. The forward-looking note here exists so the expansion is anticipated rather than discovered late.

---

## When Tests Are Run

The project commits to the following test execution policy:

### During development

Unit tests and property-based tests run on every save in the developer's IDE. They are fast enough that this does not slow development. Integration tests and platform-specific tests run on demand or before commit.

### On commit (CI)

All tests for affected modules run on commit. Tests for unaffected modules may be skipped if the build system supports it; otherwise they run too. The full suite must pass before merging.

**Target scope (as of the `0.2.0` `mrz-camera-ios` slice):** CI runs three jobs. The **`check`** job runs `./gradlew check` on a Linux runner — compiling and *testing* the **JVM target** (the common test suite is target-portable). A separate **`android-compile`** job provisions the Android SDK on a Linux runner and runs `./gradlew compileAndroidMain :mrz-camera-android:testAndroidHostTest :mrz-camera-android:verifyMlKitBundledModel`, so every module's **Android target is compile-checked on every commit** (compile-only, no emulator and no instrumented tests), `mrz-camera-android`'s Android-only host tests are *executed* (the `classifyCameraState` recoverable/terminal classification — it has no `jvm` target to carry them in `check`), and the **`verifyMlKitBundledModel` guard** asserts ML Kit stays the *bundled* (offline) text-recognition model — failing the build if a dependency change swaps in the Play-Services-delivered variant (see [`reading-risks.md`](reading-risks.md) "Third-party OCR dependency surface"). The **`ios-compile`** job runs on a macOS runner (the only runner that can build Apple targets — Kotlin/Native links against the Xcode toolchain) and runs `./gradlew iosSimulatorArm64Test compileKotlinIosArm64 :mrz-camera-ios:assembleTesseraDebugXCFramework`, so every module's **iOS targets are compile-checked and the common host tests are *executed* on the `iosSimulatorArm64` Simulator**, and the **`Tessera` XCFramework's `export(...)` surface is assembled** (so a broken export — e.g. a dropped `api` dependency — fails CI rather than the 0.2.0 release; [ADR-019](decisions/0019-ios-distribution-via-spm.md)), on every commit. So *test execution* now covers both the JVM and the iOS-simulator target, the Android target is *compile-verified*, and the only mobile gap left to a developer machine / device is **on-device and emulator/simulator camera tests** (the Simulator has no camera; a live lens is validated on a physical device — see "Testing layers" in [`../.claude/rules/mobile-dev-workflow.md`](../.claude/rules/mobile-dev-workflow.md)).

Alongside the three build jobs, a **dependency CVE gate** runs on every pull request: `dependency-review` ([`actions/dependency-review-action`](https://github.com/actions/dependency-review-action)) **fails a PR that introduces a moderate-or-higher CVE** and surfaces license information, while `dependency-submission` ([`gradle/actions/dependency-submission`](https://github.com/gradle/actions/blob/main/docs/dependency-submission.md)) submits the full transitive Gradle graph so the already-enabled Dependabot alerts see the whole tree. Both are first-party actions, matching CI's no-third-party-scanner-on-the-supply-chain stance. (Mechanism and rationale: `docs/open-questions.md` "CI and repository hardening", item 5.)

### On release candidate

The full suite, including platform-specific tests on real or virtual devices, runs before any release candidate is tagged.

### Pre-public-release security review

Before the 1.0.0 public release, a security review pass runs as a separate activity (not part of the normal test suite). It is documented in `open-questions.md`.

---

## Test Naming and Organization

### Naming

Test names describe the behavior being tested, not the implementation. Names are written as full sentences when verbose, or as `describe-the-behavior` patterns when concise.

Examples of good names:

- `parses_valid_TD3_passport_correctly`
- `returns_PartialSuccess_when_check_digit_fails`
- `generates_long_document_number_extension_for_TD3`

Examples of names that would be revised:

- `testParser1` (no behavior named)
- `parserWorks` (vacuously true)
- `testEdgeCase` (which edge case?)

### Organization

Tests are organized by the public API they exercise, not by the internal implementation. A test for the parser lives in `MrzParserTest.kt`, regardless of which internal class actually contains the logic being exercised.

Property-based tests live in dedicated files (e.g., `CheckDigitPropertyTest.kt`) because their structure is different from example-based tests.

---

## How New Tests Are Added

When adding a new feature:

1. Write tests for the feature alongside the implementation, not after
2. Cover every public API, every error path, every documented behavior
3. Add a property-based test if there is an obvious invariant
4. Add at least one synthetic-fixture-based test for each supported format the feature interacts with

When fixing a bug:

1. Write a test that reproduces the bug first (the test should fail)
2. Fix the bug (the test should now pass)
3. Verify that no other tests broke

This is standard test-first discipline; the project commits to it because it produces better tests and better fixes than ad-hoc testing.

### Coverage backstop

The coverage commitments above ("What We Commit to Testing") are checked on each change by the read-only `qa-coverage-reviewer` subagent ([`.claude/agents/qa-coverage-reviewer.md`](../.claude/agents/qa-coverage-reviewer.md)): given a diff, it flags any new public API, error type, MRZ format, transliteration profile, or validator that appears to lack a test. It checks *presence* (is there a test?), never *effectiveness* (is the test any good?) — effectiveness stays human judgment — and it does not enforce a coverage percentage (a non-commitment above). It is a backstop a maintainer invokes, not an automated gate: CI *runs* the tests, the agent *notices the missing ones*. This is why the coverage rule lives partly in a human-invoked reviewer rather than entirely in the build — the "is there a test for this new surface?" cross-reference is judgment a Gradle gate cannot perform.

---

## Related Principles

- **Principle 4 (Honest about what we know)** — tests are how we verify our claims; without them, claims are aspirations
- **Principle 5 (Transparency)** — `passedChecks` in validation results exposes what was verified, not just what failed; tests should reflect this
- **Principle 7 (Fail loudly, fail informatively)** — tests verify that errors are typed and contextual, not generic
- **Principle 10 (Privacy by default)** — synthetic fixtures only; no real document data ever
- **Principle 11 (Internal packages first)** — testing structure follows code structure; modular code enables modular tests

---

## Related Documents

- `principles.md` — the foundational principles tests verify in practice
- `conventions.md` — naming and contribution conventions, including for tests
- `architecture.md` — module structure that test organization mirrors
- `mrz-data-model.md` — the types tests construct and assert against
- `mrz-error-taxonomy.md` — the error types every error path test produces
- `mrz-parsing.md` — the parsing behavior tests verify
- `mrz-generation.md` — the generation behavior, paired with parsing in round-trip tests
- `mrz-validation.md` — validation behavior, including the `passedChecks` exposure
- `open-questions.md` — including the security review pass commitment
