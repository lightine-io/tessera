# Reading Risks

This document describes the risk profile of each reading method the SDK supports. For each method, it explains what the method establishes about the data, what it does not establish, what attack patterns are realistic, and what additional verification consumers might want to layer on top.

The document exists because the SDK never decides for the consumer whether a given reading method is "safe enough" for a given use case (Principle 1 — Reader, not oracle). The consumer has the context — the use case, the threat model, the regulatory environment, the trust boundaries — that the SDK does not. This document gives consumers the information they need to decide.

This document is living. As reading methods evolve and as security thinking matures, the content evolves with it. The list of attack patterns is non-exhaustive — it captures realistic concerns we have considered, not every theoretical possibility.

---

## How to Read This Document

Each reading method has its own section answering four questions:

- **What this method establishes** — the things you can rely on if the method completed successfully
- **What this method does not establish** — the things this method cannot tell you, regardless of how successfully it completed
- **Realistic attack patterns** — common ways the data could be wrong despite the method working as designed
- **Additional verification consumers might add** — suggestions for layering on top, not requirements

After the per-method sections, "General Considerations" covers cross-cutting concerns that apply to multiple methods.

This is not a security guarantee. It is a starting point for consumers' own threat modeling. Consumers with high-stakes use cases (border control, financial onboarding, regulatory KYC) should perform their own analysis appropriate to their threat model.

---

## Live Camera Reading

The SDK reads the MRZ from a live camera feed: it analyzes camera frames, detects the MRZ region, performs OCR, and parses the result.

### What this method establishes

- The MRZ string the SDK returns is what the camera saw at the moment of capture
- If validation passes, the MRZ is structurally well-formed and check digits compute correctly
- The data was captured through the consumer application's camera at runtime — it is not a stored value being replayed at the SDK level

### What this method does not establish

- That the document is genuine — a printed image of an MRZ, a screen displaying an MRZ, or a forged document can all produce a successful read
- That the document belongs to the person presenting it
- That the document is currently valid (not revoked, not reported lost or stolen)
- That the data on the chip (if any) matches what is printed

### Realistic attack patterns

- **Presentation of a printed copy or screen.** A photograph or screen displaying a valid MRZ produces a successful read. The camera cannot distinguish a real document from a high-quality reproduction.
- **Presentation of a real document belonging to someone else.** The MRZ on a stolen or borrowed real document reads correctly; the SDK cannot tell whether the bearer is the rightful holder.
- **Document tampering above the OCR layer.** A document with a tampered MRZ produces whatever the tampered text says, with valid-looking check digits if the tamperer recomputed them.

### Additional verification consumers might add

- Compare the MRZ against the chip data (when NFC is available and the chip is present)
- Compare the holder's appearance against the document photo (manual review or face matching)
- Cross-check the document number against an external registry (lost/stolen document databases, if accessible)
- Apply liveness detection to confirm a real person is presenting the document, not a recording

---

## Pre-Captured Image Reading

The SDK reads the MRZ from a saved image (a photo file, a scanned image, or any pre-existing image source). This capability is opt-in and disabled by default.

### What this method establishes

- The MRZ string the SDK returns is what the image contains
- If validation passes, the MRZ is structurally well-formed and check digits compute correctly

### What this method does not establish

- When the image was captured
- Where the image came from
- Whether the image is the original or a copy
- Anything that live camera reading does not establish (all the same limitations apply)
- That the image was captured by the consumer application — it could have been received from anywhere

### Realistic attack patterns

- **All attack patterns from live camera apply.** Saved images can show printed copies, screens, or forged documents.
- **Replay of a previously captured image.** An image from a previous successful capture can be re-submitted indefinitely.
- **Manipulation of the image before submission.** Image editing tools can alter the MRZ region; if the alteration preserves check digit consistency, validation passes.
- **Sourcing of images from outside the consumer application.** An image obtained from any source — email, file share, web download — can be presented to the SDK as if it were a document scan.

### Why this method is opt-in

The opt-in default exists because pre-captured images carry meaningfully more risk than live camera reading. The consumer application loses the implicit guarantee that the data was captured "just now" through hardware the application controls. By requiring explicit enablement, the SDK makes the consumer acknowledge they have considered these risks.

### Additional verification consumers might add

- Verify the source of the image (file metadata, capture context, signed image envelopes if available)
- Apply image forensics to detect editing
- Compare against chip data when possible
- Combine with separate live capture (the saved image for record, a live capture for verification)

---

## Manual Entry

The user types the MRZ data (or chip access keys) directly. No camera or chip is involved.

### What this method establishes

- The data the SDK returns is what the user typed
- If validation passes, the typed data is structurally well-formed and check digits compute correctly

### What this method does not establish

- That a document was actually present
- That the user is reading from a real document at all
- That the data corresponds to any specific person

### Realistic attack patterns

- **Fabrication of plausible MRZ data.** A user with knowledge of the MRZ format can type valid-looking data that does not correspond to any real document. Check digit validation passes if the user computed the digits correctly.
- **Use of MRZ data from a different document.** A user with access to someone else's document data can type that data without having the document present.
- **Typo-driven errors.** A user typing real data may make mistakes; check digit validation catches some but not all errors.

### Why manual entry is still a first-class capability

Despite these risks, manual entry is intentionally a first-class reading method, not just a fallback. Real use cases require it: accessibility, low-light conditions, damaged documents, contexts where camera access is impractical, contexts where users must enter data themselves for compliance reasons. The SDK does not refuse to support these use cases; it documents the risk profile and lets consumers decide.

### Additional verification consumers might add

- Require manual entry to be combined with another method (e.g., manual entry of access keys followed by NFC chip read)
- Apply business rules that constrain valid input (e.g., known document number ranges)
- Cross-check against external registries
- Require human-in-the-loop verification for entries that succeed without other corroboration

---

## NFC Chip Reading

The SDK reads electronic data from documents with NFC chips, using BAC or PACE access protocols.

### What this method establishes

- The data the SDK returns came from a chip that responded to the BAC or PACE protocol with the access keys provided
- The data passed structural parsing per the chip data format specifications
- If the chip's Security Object signature is verified (a future capability), the data was signed by the issuing authority

### What this method does not establish (in this release)

- Until cryptographic signature verification is added, the SDK does not verify that the chip's data is authentic; an attacker capable of producing a chip that responds to BAC/PACE with arbitrary data could pass an unverified read
- That the chip belongs to the person presenting the document
- That the document is currently valid (not revoked, not reported lost or stolen)
- That the printed MRZ matches the chip data unless explicitly compared

### Realistic attack patterns

- **Cloned or fabricated chips.** Without signature verification, a chip producing the right data structure with valid access keys is accepted. The chip itself could be a clone or a fabrication.
- **Replay of recorded chip sessions.** BAC and PACE include session-binding mechanisms that mitigate this, but consumers should be aware that protocol implementations vary.
- **MRZ-vs-chip mismatch.** A chip with different data than the printed MRZ may indicate tampering of one or the other. The SDK reports this as a warning; the consumer decides what it means.
- **Documents with disabled or destroyed chips.** A document where the chip cannot be read does not necessarily mean the document is invalid; the printed MRZ may still be authentic.

### Additional verification consumers might add

- Once cryptographic signature verification is available, use it (this is a Beyond-1.0 capability — see scope.md)
- Compare chip data against the printed MRZ (the SDK surfaces mismatches as warnings)
- Cross-check the document number against external registries
- For high-stakes use cases, require both successful chip read and successful signature verification

---

## Backend / Server-Side Parsing

The SDK is invoked on a string in a backend service, with no camera, NFC, or user interaction involved at the SDK layer. This is a usage pattern that emerges from the architecture, not a separate reading method.

### What this method establishes

- The data the SDK returns is what the input string contained
- If validation passes, the input is structurally well-formed and check digits compute correctly

### What this method does not establish

- Where the input string came from
- Whether the input was generated by an authentic device, fabricated by software, or replayed from a previous capture
- Anything about the original document, the bearer, or the capture context

### Realistic attack patterns

The risk profile depends entirely on **where the input came from before reaching the backend**. Some scenarios:

- **Input from a trusted client SDK with attestation.** Lower risk; the chain of custody is established outside the SDK.
- **Input from a user-submitted form on a public website.** Highest risk; the user can submit anything that is structurally valid.
- **Input from a partner system over an authenticated channel.** Risk depends on the partner's own input controls.
- **Input from a batch processing pipeline of stored data.** Risk depends on the integrity of the storage and the original capture.

The SDK has no visibility into any of this. The consumer is fully responsible for understanding their own input chain.

### Additional verification consumers might add

- Authenticate the source of the input
- Apply rate limits and anomaly detection appropriate to the source
- Require additional evidence for high-stakes decisions (e.g., live chip verification, in-person verification)
- Maintain audit logs of submissions and their sources

---

## General Considerations

These concerns apply across multiple reading methods.

### The SDK's Boundary

The SDK reports what it observed. It does not authenticate the consumer's runtime environment, verify that the calling code is unmodified, or detect that an attacker has hooked into the consumer application. Defending the integrity of the host application is the consumer's responsibility — this is consistent with Principle 6 (Defense in depth, not security theater).

### Combining Methods

Many use cases benefit from combining methods. MRZ from camera plus chip data from NFC provides cross-verification that neither method alone offers. Manual entry of access keys plus chip read provides confirmation that the user has the document in hand. The SDK's read-method metadata makes these combinations visible to the consumer.

### Time-of-Reading vs Time-of-Decision

A successful read at time T does not guarantee anything at time T+1. A document that was valid five minutes ago may have been reported lost since. A chip that read successfully may be revoked tomorrow. Consumers making decisions based on cached read results should consider whether their use case tolerates that latency.

### Consumer Responsibility

The recurring theme across all reading methods is that the SDK reports observations and the consumer makes trust decisions. This is intentional. The SDK has no insight into the consumer's threat model, regulatory environment, or business context. Consumers who treat any single reading method as a definitive trust signal are taking on risk the SDK does not pretend to mitigate.

---

## Areas for Further Analysis

The following are concerns we have considered but not quantified or fully analyzed. They are tracked here as candidates for future security work — not as immediate risks consumers must address, but as questions that may deserve deeper investigation as the project matures.

### Side-channel observation during NFC reading

NFC chip reading involves cryptographic operations. Side-channel attacks (timing, power analysis, electromagnetic emanation) against the chip itself are a known concern for high-assurance contexts. The SDK does not enable or facilitate such attacks, but the protocol exposure exists. Whether this matters depends on the consumer's threat model.

### Implementation-level vulnerabilities in cryptographic operations

BAC and PACE involve specific cryptographic protocols. Implementation errors (weak random number generation, improper key derivation, mishandling of session binding) could undermine the protocol's security properties. The SDK uses platform cryptographic APIs to mitigate this, but the platform implementations themselves are outside the SDK's control.

### Memory disclosure attacks

Sensitive data (MRZ contents, chip data, cryptographic keys) lives briefly in memory. The SDK commits to clearing this data promptly, but a sufficiently capable attacker (kernel-level access, debugger attached) could observe it during the brief window. Hardware-backed key storage (where available) reduces but does not eliminate this concern.

The camera analyse-frame core (`mrz-camera-core`, 0.2.0) is built to minimize this window: it does no persistence and no network, holds **no reference to the camera frame** after a frame is analysed, and its telemetry carries diagnostics only, never recognized text or field values. The owns-the-camera-session streaming layer (`MrzCameraScanner` / `CameraXMrzScanner`, 0.2.0) closes each `ImageProxy` immediately after its frame is analysed — both to honour CameraX's contract (the next frame is not delivered until the current one is closed) and to keep the buffer's lifetime no longer than the analysis itself. The end-to-end frame-buffer behaviour of the live capture pipeline on a real device was confirmed in the live-device slice: frames streamed continuously with each `ImageProxy` released promptly and no stall — a retained (unclosed) buffer would have stalled the pipeline once CameraX's buffer depth filled.

The iOS owns-the-camera-session layer (`AVCaptureMrzScanner`, 0.2.0) applies the same minimal-lifetime discipline to AVFoundation's `CMSampleBuffer`. Because a `CMSampleBuffer` is a Core Foundation type that Kotlin/Native's ARC does not manage, the scanner takes explicit ownership: it `CFRetain`s each buffer before handing it across the coroutine boundary and `CFRelease`s it exactly once — after the frame is analysed, or when the frame is dropped (a newer one arrived) or the session tears down — so a frame is never held longer than its analysis, and no buffer leaks. AVFoundation's `alwaysDiscardsLateVideoFrames` further bounds how many frames are live at once. This retain/release accounting is verified by compilation on CI; its behaviour under a live device's frame load is device-verified separately, since the iOS Simulator has no camera.

### Optical attacks against camera reading

Specialized lighting, reflective overlays, or carefully crafted document features could potentially cause OCR to misread in predictable ways. The SDK uses platform OCR engines, which have their own robustness characteristics; we do not currently analyze whether specific optical attacks against those engines are practical.

### Third-party OCR dependency surface

The Android OCR engine is Google's ML Kit Text Recognition (bundled model — the model ships in the app, with no Play Services dependency for model delivery and no network needed to recognize text; a CI guard, `verifyMlKitBundledModel`, mechanically enforces this by failing the build if a dependency change ever swaps in the Play-Services-delivered variant). Its artifact does, however, transitively pull in Google's data-transport stack (`com.google.android.datatransport:transport-backend-cct` / `transport-runtime`) and Firebase encoder components — infrastructure capable of reporting usage telemetry to Google. The SDK initializes none of it (no `FirebaseApp`, no transport registration) and the model recognizes text on-device; whether that infrastructure ever transmits depends on the host application's own Google/Firebase initialization, and in the common case (no Firebase configured) it is inert. We have confirmed the dependency is present in the transitive graph but have not runtime-traced its behavior. A consumer with a strict zero-network-egress requirement should treat this as a dependency-audit item, not a settled "no egress" guarantee. The iOS OCR engine (Apple Vision) is a first-party system framework with no comparable transitive surface.

### Supply chain and dependency integrity

The SDK depends on platform cryptographic and OCR APIs, plus a small set of explicitly chosen libraries. A compromised dependency could undermine any security property. This is a concern for the project's own security posture — addressed through a per-PR dependency CVE gate (`dependency-review-action`, failing on moderate-or-higher CVEs), a locked version catalog, SHA-pinned GitHub Actions, and Dependabot alerts on the full transitive graph — but not something the SDK can defend against from within.

### Future analysis pass

These items are candidates for systematic analysis during a security review pass before public release. That commitment is tracked separately in `open-questions.md`.

---

## Related Principles

- **Principle 1 (Reader, not oracle)** — the SDK reports observations; the consumer makes trust decisions. This document gives consumers what they need to make those decisions.
- **Principle 4 (Honest about what we know)** — the document distinguishes what we are confident about from what is uncertain or speculative
- **Principle 5 (Transparency)** — the document exposes the SDK's actual capabilities and limitations; nothing is hidden behind reassuring language
- **Principle 6 (Defense in depth, not security theater)** — the document is honest about what the SDK can and cannot defend against; it does not claim guarantees we cannot honestly support

---

## Related Documents

- `principles.md` — the foundational principles this document references
- `scope.md` — what reading methods the SDK supports and when
- `mrz-data-model.md` — the `ReadMethod` metadata that records which method produced each result
- `open-questions.md` — tracking deferred analysis items
