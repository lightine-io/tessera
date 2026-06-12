# Feature Guides

Usage guides for every SDK capability. Each guide explains what the capability does, shows a copy-paste **Usage** example verified against the shipped API, documents the public contract, and states the current implementation status — so a guide is both the how-to and the reference for its capability.

If you are new to the SDK, start with [Getting Started](../getting-started.md) (dependency → parse → validate → generate in ten minutes), then the parsing guide — most integrations begin there. For platform integration (Android camera scanning, iOS), see the README's installation section and the camera-reading guide below.

## Core MRZ operations

| Guide | What it covers |
|---|---|
| [MRZ Parsing](mrz-parsing.md) | Turning a raw MRZ string into a structured document — the most-used operation; where most integrations begin. Auto-detect and per-format entry points, strictness, input forms. |
| [MRZ Validation](mrz-validation.md) | Checking MRZ data against ICAO Doc 9303 — runs implicitly in the parser/generator and standalone for already-extracted data. Check digits, semantic checks, warnings. |
| [MRZ Generation](mrz-generation.md) | Turning structured input into a valid MRZ string — test fixtures, issuance flows, round-trip verification. |
| [MRZ Data Model](mrz-data-model.md) | The types everything else produces and consumes: `MrzDocument` and the per-format documents, `CommonFields`, dates, read-method metadata. |
| [MRZ Error Taxonomy](mrz-error-taxonomy.md) | The three-tier model of errors, validation failures, and warnings every MRZ feature adheres to, with representative examples. |

## Reading methods

| Guide | What it covers |
|---|---|
| [MRZ Camera Reading](mrz-camera-reading.md) | Headless live-camera MRZ scanning on Android (CameraX + ML Kit) and iOS (AVFoundation + Apple Vision): the analyse-frame core, the owns-the-session scanners, parsing modes, quality signals, capture errors. |

## Reference data and infrastructure

| Guide | What it covers |
|---|---|
| [Lookup Tables](lookup-tables.md) | The reference data the SDK ships: country codes and document type codes, and how recognition informs parsing, validation, and generation. |
| [Transliteration](transliteration.md) | Converting names into the restricted MRZ alphabet during generation — the ICAO default mapping and per-country profiles. |
| [Telemetry](telemetry.md) | The pluggable diagnostics interface: the consumer-implemented sink contract, the no-op default, registration, and redaction utilities. |

## Related reading

- [`../scope.md`](../scope.md) — what the SDK supports, what it deliberately does not, and the release roadmap.
- [`../reading-risks.md`](../reading-risks.md) — what each reading method establishes about the data and what it cannot; read this before trusting any single method.
- [`../glossary.md`](../glossary.md) — MRZ, TD3, eMRTD, and the rest of the vocabulary.
