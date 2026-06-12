# Glossary

This document defines terms used throughout the project documentation. It is a quick reference, not a substitute for the documents that explain concepts in depth. Where a term is the subject of a dedicated document or section, the entry points there.

The glossary covers:

- Technical terms specific to MRZ, eMRTD, and identity documents
- Project-specific terms coined for this SDK
- Abbreviations and acronyms used in the documentation

It does not cover general programming concepts (API, module, class, etc.) or platform-specific terminology that belongs in platform documentation.

This document is living. Terms are added as they appear in documentation and removed if they fall out of use.

---

## A

### ADR (Architecture Decision Record)

A document capturing a significant project decision in a stable, reviewable format. ADRs include the context, the decision, the consequences, and the alternatives considered. See `conventions.md` for the format and `decisions/` for the records themselves.

### Apple Vision

Apple's on-device image-analysis framework (the `Vision` framework). The `mrz-camera-ios` module's `VisionMrzTextRecognizer` uses Vision's `VNRecognizeTextRequest` (recognition level *accurate*, language correction *off* — the MRZ is not natural language, so correcting it would change characters) to OCR each camera frame's pixel buffer — the iOS counterpart to Android's ML Kit recognizer. Vision runs on the iOS Simulator on a supplied image (the Simulator has no camera). See `mrz-camera-reading.md`.

### Available Since

A version marker on public APIs and types indicating the release in which the item first appeared. See `versioning.md` for the convention.

---

## B

### BAC (Basic Access Control)

A protocol defined by ICAO Doc 9303 for authenticating access to the data on an electronic travel document chip. The protocol uses keys derived from the MRZ (specifically the document number, date of birth, and date of expiry, with their check digits). BAC is the predecessor to PACE and is supported on most existing electronic documents.

### Backend Parsing

A usage pattern where the SDK is invoked on a string in a server environment, with no camera, NFC, or user interaction at the SDK layer. Backend parsing emerges from the architecture rather than being a separately implemented capability — the core logic operates on plain string and byte data with no I/O. See the "Reading Methods" section of `scope.md`.

---

## C

### CameraX

Google's Jetpack camera library for Android. The `mrz-camera-android` module depends on CameraX `camera-core` for the `ImageProxy` frame type its ML Kit recognizer reads and feeds to the shared `mrz-camera-core` analyse-frame core; the owns-the-camera-session convenience (`CameraXMrzScanner`, 0.2.0) runs a CameraX capture session internally (`camera-lifecycle`'s `ProcessCameraProvider` + an `ImageAnalysis` use case bound to a lifecycle), with `camera-camera2` as the runtime backend. See `mrz-camera-reading.md`.

### Check Digit

A digit appended to a field in the MRZ, computed from the field's contents using the algorithm defined in ICAO Doc 9303 Part 3 Appendix A (weights 7-3-1, modulus 10). Check digits enable detection of transcription and OCR errors. The MRZ contains per-field check digits and, in some formats, a composite check digit that covers multiple fields jointly.

### Composite Check Digit

A check digit computed across multiple MRZ fields (rather than a single field). Present in some formats (notably TD3, where it appears at the end of line 2). Failure of the composite check digit indicates inconsistency across the covered fields without identifying which field is the cause.

### Country Code

A three-letter code identifying a country, organization, or other entity that issues travel documents. The MRZ uses these codes for both the issuing state and the holder's nationality. Country codes follow ISO 3166-1 alpha-3 with extensions defined in ICAO Doc 9303 Part 3 Section 5. See `lookup-tables.md`.

### Country Code Category

An SDK enum (`CountryCodeCategory`) classifying a recognized country code as a `STATE` (an ISO 3166-1 alpha-3 country), `ORGANIZATION` (a UN-style international organization), `STATELESS`, `REFUGEE`, `HISTORICAL` (a dissolved or renamed state whose documents may still circulate), or `OTHER`. Exposed on `CountryCode.category` for codes found in the SDK's country-code lookup table; the special-purpose categories follow ICAO Doc 9303 Part 3 Section 5. See `lookup-tables.md`.

---

## D

### Data Group (DG)

A structured section of data on an electronic travel document chip. Data groups are defined by ICAO Doc 9303 Part 10 and are numbered (DG1, DG2, etc.). DG1 contains the MRZ; DG2 contains the facial image; other data groups contain various biometric and biographic data depending on what the issuing state populated.

### Document Category

An SDK enum (`DocumentCategory`) giving a coarse classification of a document — `PASSPORT`, `IDENTITY_CARD`, `RESIDENCE_PERMIT`, `VISA`, or `OTHER` — derived from the MRZ document type code via the document-type lookup table. Exposed on `DocumentType.category`; it is `null` when the raw type code is not in the SDK's (deliberately non-exhaustive) lookup table. See `lookup-tables.md`.

### Document Type Code

A one or two-character code at the start of the MRZ identifying the kind of document. Single-character codes (`P`, `V`, `I`, etc.) are the legacy system; two-character codes (`PP`, `PD`, `PS`, etc.) are the current ICAO Doc 9303 system. Both are supported. See `lookup-tables.md`.

---

## E

### eMRTD (Electronic Machine Readable Travel Document)

A travel document containing both an MRZ and an electronic chip with structured data accessible via NFC. Most modern passports are eMRTDs. See ICAO Doc 9303 Part 9 and onward for the full electronic specifications.

### EXIF

A metadata format embedded in image files (notably JPEG) recording capture details such as the date and time, camera make and model, and optionally GPS coordinates. Relevant to pre-captured image reading, where EXIF metadata can inform consumer risk assessment. See `open-questions.md` for the deferred design item on metadata exposure.

---

## F

### Filler Character

The `<` (less-than sign) character used in the MRZ to pad fields and separate name components. It is one of only three character categories permitted in the MRZ (the others being uppercase A-Z and the digits 0-9).

### Format (MRZ Format)

One of the standardized MRZ layouts defined by ICAO Doc 9303: TD1, TD2, TD3, MRV-A, or MRV-B. Each format has specific line counts, line lengths, field positions, and check digit positions. See `scope.md` for the supported formats and `mrz-data-model.md` for how they appear in the data model.

---

## H

### Headless Core

An implementation of a reading method that does not include UI. Consumers integrating with a headless core provide their own UI (or no UI) and pass data through a programmatic interface. The SDK provides headless cores for every reading method, with optional UI modules layered on top for consumers who want a complete out-of-the-box experience. See `scope.md` and `architecture.md`.

---

## I

### ICAO (International Civil Aviation Organization)

The United Nations agency responsible for international civil aviation standards, including the standards for machine-readable travel documents. ICAO publishes Doc 9303 (the canonical reference for MRZ and eMRTD specifications).

### ICAO Doc 9303

The canonical ICAO publication defining the standards for machine-readable travel documents. Multi-part document:
- Part 3: specifications common to all MRTDs
- Part 4: machine-readable passports (TD3)
- Part 5: TD1 size machine-readable official travel documents
- Part 6: TD2 size machine-readable official travel documents
- Part 7: machine-readable visas (MRV-A and MRV-B)
- Part 9 and beyond: electronic machine-readable travel documents (eMRTDs), data structures, security mechanisms

The SDK references ICAO Doc 9303 as the source of truth for format specifications rather than reproducing them.

### Inference Method

An enum value on `MrzDate` describing which heuristic was used to compute the four-digit year from the MRZ's two-digit year. Possible values include `SLIDING_WINDOW_BIRTH`, `SLIDING_WINDOW_EXPIRY`, and `RAW_ONLY`. The `inferenceMethod` field exists so consumers always know whether and how a year was inferred. See `mrz-data-model.md`.

### Issuing State

The country or organization that issued the document. In the MRZ, the issuing state appears as a three-letter country code in the first line. Different from nationality (which is the holder's nationality, separately encoded).

---

## L

### LDS (Logical Data Structure)

The standardized layout of data on an eMRTD chip, defined by ICAO Doc 9303 Part 10. The LDS organizes data into data groups (DG1, DG2, etc.) and includes the Security Object that signs them collectively.

### Lenient Mode

A consumer-chosen parsing mode in the camera layer that forgives benign format noise (for example, stray whitespace) without changing any data value. Strict remains the default, and the raw OCR reading is always exposed regardless of mode. See `mrz-camera-reading.md`.

### Lookup Table

A reference dataset shipped with the SDK that maps codes to display names and metadata. The SDK ships lookup tables for country codes and document type codes. See `lookup-tables.md`.

---

## M

### Manual Entry

A reading method where the user types MRZ data (or chip access keys) directly. Manual entry is a first-class reading method, not just a fallback. See `scope.md`.

### ML Kit

Google's on-device machine-learning library for Android. The SDK uses ML Kit Text Recognition — the **bundled** Latin model, which carries the model in the app with no Google Play Services dependency and no network — as the Android OCR engine in `mrz-camera-android`. See `mrz-camera-reading.md`.

### MRTD (Machine Readable Travel Document)

A travel document conforming to ICAO Doc 9303, with a Machine Readable Zone designed for OCR and electronic processing. Includes passports, identity cards used for travel, and machine-readable visas.

### MRV (Machine Readable Visa)

A visa conforming to ICAO Doc 9303 Part 7. Two formats: MRV-A (large, two lines of 44 characters) and MRV-B (smaller, two lines of 36 characters).

### MRZ (Machine Readable Zone)

The standardized region of a travel document containing data formatted for machine reading. The MRZ uses a restricted character set (uppercase A-Z, digits 0-9, and the filler character `<`) and the OCR-B typeface. See `scope.md` for supported MRZ formats.

### MrzDate

The SDK's representation of a date encoded in the MRZ. Exposes both the raw two-digit year exactly as it appears in the MRZ and a computed four-digit year inferred via heuristic, along with an `inferenceMethod` field describing how the inference was performed. See `mrz-data-model.md`.

### MrzDocument

The SDK's top-level type representing parsed MRZ content. A sealed type with one variant per supported format (`TD1`, `TD2`, `TD3`, `MrvA`, `MrvB`). See `mrz-data-model.md`.

### MrzFormat

An enum naming the supported MRZ formats: `TD1`, `TD2`, `TD3`, `MRV_A`, `MRV_B`. See `mrz-data-model.md`.

---

## N

### NFC (Near Field Communication)

A short-range wireless protocol used to communicate with the chip in an electronic travel document. NFC chip reading is in the SDK's scope but ships in a later release than MRZ reading. See `scope.md`.

---

## O

### OCR (Optical Character Recognition)

Software that recognizes printed characters from images. The SDK uses platform OCR engines (ML Kit on Android, Apple Vision on iOS) to extract MRZ text from camera frames or pre-captured images.

### OCR-B

The typeface specified by ICAO Doc 9303 for the MRZ. Designed for reliable optical character recognition. The MRZ in any conformant document is printed in OCR-B.

### Optional Data

A field in the MRZ defined as available for the issuing state's discretionary use. Different formats have different optional data fields (TD1 has two, TD3 has the personal number, etc.). The SDK exposes these fields verbatim per Principle 1.

---

## P

### PACE (Password Authenticated Connection Establishment)

A protocol defined by ICAO Doc 9303 for authenticating access to electronic travel document chips, using a Diffie-Hellman key agreement with a password derived from MRZ data or a CAN (Card Access Number). PACE is the modern successor to BAC and provides stronger security properties.

### Personal Number

A field in the TD3 MRZ format (passport booklets) typically used by issuing states to record a national identification number or other internal reference number. The personal number has its own check digit. Some issuing states leave the check digit as the filler character `<` even when the personal number is populated; the SDK exposes the field verbatim.

### Pre-Captured Image Reading

A reading method where the SDK extracts the MRZ from a saved image file rather than a live camera feed. This capability is opt-in by consumer configuration; the default does not allow it. See `scope.md` and `reading-risks.md` for the rationale and risks.

### Primary Identifier

The first part of the name field in the MRZ, typically the surname or family name. Separated from the secondary identifier by `<<`.

### Profile (Transliteration Profile)

A named, documented set of character-mapping rules for converting characters outside the MRZ alphabet to MRZ-compatible representations. The SDK ships profiles based on ICAO defaults and country-specific conventions; consumers can register their own. See `transliteration.md`.

---

## R

### Read Method

The way the SDK obtained a piece of MRZ data. Represented in result metadata as a `ReadMethod` enum: `LIVE_CAMERA`, `PRE_CAPTURED_IMAGE`, `MANUAL_ENTRY`, `NFC_CHIP`, `BACKEND_STRING_INPUT`, or `MIXED`. See `mrz-data-model.md` and `reading-risks.md`.

### Result Metadata

A structured aggregate accompanying every SDK result, capturing the read method, validation failures, warnings, and optional timing information. Exists separately from the data so consumers can inspect how a result was produced and what observations the SDK made. See `mrz-data-model.md`.

---

## S

### Sealed Type

A type whose set of subtypes is closed and known at compile time. Used in the SDK for `MrzDocument` (variants per format), `ParseResult` (success/partial-success/failure variants), and the error taxonomy. Enables exhaustive matching by consumers. See `mrz-data-model.md`.

### Secondary Identifier

The second part of the name field in the MRZ, typically the given names. Follows the primary identifier and the `<<` separator.

### Security Object (SOD)

A signed data structure on an eMRTD chip containing hashes of the chip's data groups, signed by the issuing authority. The SOD enables verification that the data on the chip was placed there by the issuing authority and has not been modified. SOD parsing is structural in the initial release; cryptographic signature verification is a Beyond-1.0 capability. See `scope.md`.

### Sliding Window (Heuristic)

A heuristic for inferring the four-digit year from a two-digit year in the MRZ. The SDK supports two sliding-window variants: `SLIDING_WINDOW_BIRTH` for birth dates (assumes the most recent past century such that the date is plausible) and `SLIDING_WINDOW_EXPIRY` for expiry dates (assumes the century that places the date in the future or recent past). See `mrz-data-model.md`.

### Strict Mode

The parser's default behavior, in which any deviation from ICAO Doc 9303 structural conformance produces a parse error or a typed validation failure. A consumer-chosen lenient mode ships in the camera layer (see Lenient Mode); tolerant mode remains deferred (see Tolerant Mode). See `mrz-parsing.md` and `mrz-camera-reading.md`.

### Swift Package Manager (SPM)

Apple's first-party dependency manager, and the SDK's iOS distribution channel: iOS consumers add the `tessera-swift` package, which wraps the Tessera XCFramework attached to each GitHub release. See the iOS integration guide and `decisions/0019-ios-distribution-via-spm.md`.

---

## T

### TD1

The ICAO Doc 9303 MRZ format for credit-card-sized documents (national ID cards, residence permits, certain official travel documents). Three lines of 30 characters. See ICAO Doc 9303 Part 5.

### TD2

The ICAO Doc 9303 MRZ format for mid-sized documents (some official travel documents, older ID cards). Two lines of 36 characters. See ICAO Doc 9303 Part 6.

### TD3

The ICAO Doc 9303 MRZ format for passport booklets. Two lines of 44 characters. The most common MRZ format in everyday use. See ICAO Doc 9303 Part 4.

### Tolerant Mode

A planned — not yet shipped — parsing mode that would use check-digit-guided disambiguation to recover from common OCR confusions. When built, it must *surface* candidate corrections rather than silently overwrite values (reader, not oracle). Deferred to the still-image reading work. See `open-questions.md` ("Lenient and tolerant parsing modes").

### Telemetry

Diagnostic information about SDK operation that consumers may collect via a pluggable interface. The SDK exposes a telemetry contract; consumers provide implementations. The default is a no-op. The SDK has no built-in telemetry destination and does not phone home. See `architecture.md` (the `telemetry` module).

### Transliteration

The conversion of characters outside the MRZ alphabet (uppercase A-Z, digits, filler) into MRZ-compatible representations. Different issuing states have different conventions for the same character; the SDK handles this through profile-based configuration. See `transliteration.md`.

### Truncation

Shortening of a name to fit within the MRZ's name field width. ICAO Doc 9303 defines specific rules for truncation, including a truncation indicator character. The SDK detects truncation and exposes it as a flag on the data model and as a warning. See `mrz-data-model.md` and `mrz-error-taxonomy.md`.

---

## V

### Validation Failure

Data that was extracted from the MRZ but does not conform to the relevant specification. Validation failures accompany the extracted data rather than replacing it; the consumer reads both and decides. Distinct from errors (operations that could not complete) and warnings (data is valid but anomalous). See `mrz-error-taxonomy.md`.

### Verbatim Extraction

The principle that the SDK reproduces field values exactly as they appear in the source data, without correction, normalization, or interpretation. A misspelled name remains misspelled; an unexpected value is exposed as-is. See Principle 1 in `principles.md`.

---

## W

### Warning

Data that is structurally valid and conforms to the MRZ specification, but is anomalous in a way the consumer might want to know about (e.g., expired document, truncated name, MRZ-vs-chip mismatch). Warnings appear in result metadata; consumers may surface them, log them, or ignore them. Distinct from errors and validation failures. See `mrz-error-taxonomy.md`.

---

## X

### XCFramework

Apple's multi-architecture binary framework format. The SDK's iOS build ships as a single `Tessera` XCFramework (device and simulator slices), attached as a zip to each GitHub release and consumed through Swift Package Manager. See `mrz-camera-reading.md` and `decisions/0019-ios-distribution-via-spm.md`.

---

## Related Documents

- `principles.md` — the foundational principles this glossary refers to throughout
- `scope.md` — what the SDK supports
- `architecture.md` — module structure and dependency graph
- `mrz-data-model.md` — the data model where many of these types are defined
- `mrz-error-taxonomy.md` — the error, validation failure, and warning taxonomy
- `reading-risks.md` — the risk profile for each reading method
