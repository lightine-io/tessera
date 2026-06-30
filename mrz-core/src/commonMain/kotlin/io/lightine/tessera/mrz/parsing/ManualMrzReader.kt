package io.lightine.tessera.mrz.parsing

import io.lightine.tessera.types.vocabulary.ReadMethod
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Reads an MRZ a human typed in — the manual-entry reading method (release 0.4.0+).
 *
 * Manual entry is the degenerate reading method: the consumer already holds the exact MRZ string,
 * so this runs the very same parse + validate pipeline as [MrzParser] and surfaces the identical
 * verdict — a typed-in MRZ validates exactly as a camera- or saved-image-read one does. The only
 * difference is provenance: every result reports
 * [`ReadMethod.MANUAL_ENTRY`][io.lightine.tessera.types.vocabulary.ReadMethod.MANUAL_ENTRY] (a human
 * typed it) rather than the
 * [`BACKEND_STRING_INPUT`][io.lightine.tessera.types.vocabulary.ReadMethod.BACKEND_STRING_INPUT] that
 * [MrzParser] reports for an application-supplied string (Principle 5 — report how the data reached
 * the SDK). A backend or batch caller that produced the string itself keeps using [MrzParser]
 * directly.
 *
 * The entry points mirror [MrzParser]:
 *
 * 1. **Auto-detecting** — [read]. Detects the format from the input's line count and per-line
 *    lengths.
 * 2. **Format-specific** — [readTD3], [readTD2], [readTD1], [readMRVA], [readMRVB]. Use these when
 *    the input UI already knows the document type (a passport-entry field knows it is TD3), so a
 *    mis-typed line reports [`MrzInvalidLength`][io.lightine.tessera.types.errors.MrzInvalidLength]
 *    against the expected dimensions rather than the less specific
 *    [`MrzFormatNotDetected`][io.lightine.tessera.types.errors.MrzFormatNotDetected].
 *
 * Each entry point optionally accepts a [`referenceTime`][kotlin.time.Instant] for date-window
 * inference, exactly as [MrzParser] does. The returned [ParseResult] is the parser's, with only
 * [`ResultMetadata.readMethod`][ResultMetadata] restamped; the document, warnings, and validation
 * failures are surfaced unchanged (reader, not oracle — manual entry adds no judgement).
 *
 * See [`docs/features/mrz-parsing.md`](https://lightine.youtrack.cloud/articles/TES-A-25) for the
 * full feature description.
 */
public object ManualMrzReader {
    /** Reads a typed MRZ by auto-detecting its format. See [MrzParser.parse]. */
    public fun read(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parse(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /** Reads pre-split typed MRZ lines by auto-detecting the format. See [MrzParser.parse]. */
    public fun read(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parse(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /** Reads a typed MRZ as TD3 (passport). See [MrzParser.parseTD3]. */
    public fun readTD3(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseTD3(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /** Reads pre-split typed TD3 lines. See [MrzParser.parseTD3]. */
    public fun readTD3(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseTD3(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /** Reads a typed MRZ as TD2. See [MrzParser.parseTD2]. */
    public fun readTD2(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseTD2(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /** Reads pre-split typed TD2 lines. See [MrzParser.parseTD2]. */
    public fun readTD2(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseTD2(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /** Reads a typed MRZ as TD1 (identity card). See [MrzParser.parseTD1]. */
    public fun readTD1(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseTD1(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /** Reads pre-split typed TD1 lines. See [MrzParser.parseTD1]. */
    public fun readTD1(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseTD1(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /** Reads a typed MRZ as MRV-A (Type-A visa). See [MrzParser.parseMRVA]. */
    public fun readMRVA(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseMRVA(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /** Reads pre-split typed MRV-A lines. See [MrzParser.parseMRVA]. */
    public fun readMRVA(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseMRVA(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /** Reads a typed MRZ as MRV-B (Type-B visa). See [MrzParser.parseMRVB]. */
    public fun readMRVB(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseMRVB(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /** Reads pre-split typed MRV-B lines. See [MrzParser.parseMRVB]. */
    public fun readMRVB(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseMRVB(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)
}

/**
 * Restamps [readMethod] onto a parser result, copying the metadata and changing only the read
 * method — the document, warnings, and validation failures are surfaced unchanged. Used by the
 * provenance-aware reading methods in `mrz-core` (e.g. [ManualMrzReader]) that delegate to
 * [MrzParser] — which always reports `BACKEND_STRING_INPUT` because it sees only a string — but
 * know the data's true provenance (Principle 5). The `mrz-camera-core` analyse-frame core does the
 * same with its own copy (module-private; an `internal` helper cannot cross the module boundary).
 */
internal fun ParseResult.withReadMethod(readMethod: ReadMethod): ParseResult {
    val stamped = metadata.copy(readMethod = readMethod)
    return when (this) {
        is ParseResult.Success -> copy(metadata = stamped)
        is ParseResult.PartialSuccess -> copy(metadata = stamped)
        is ParseResult.Failure -> copy(metadata = stamped)
    }
}
