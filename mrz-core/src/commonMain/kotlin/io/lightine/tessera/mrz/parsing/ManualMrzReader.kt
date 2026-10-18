package io.lightine.tessera.mrz.parsing

import io.lightine.tessera.types.vocabulary.ReadMethod
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Reads an MRZ a person typed in; reports `MANUAL_ENTRY` as the reading method.
 *
 * It runs the same parse and validation as [MrzParser]. Only [ResultMetadata.readMethod] differs.
 *
 * @since 0.4.0
 */
public object ManualMrzReader {
    /**
     * Reads typed MRZ text, detecting the format from its line count and line lengths.
     *
     * @param input The MRZ as one string, with the lines separated by a line break.
     * @param referenceTime The moment that picks the century of two-digit years and judges expiry.
     *   Default: the system clock.
     * @return The result of [MrzParser.parse], with the reading method set to `MANUAL_ENTRY`.
     */
    public fun read(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parse(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /**
     * Reads typed MRZ lines, detecting the format from their count and lengths.
     *
     * @param input The MRZ lines.
     * @param referenceTime The moment that picks the century of two-digit years and judges expiry.
     *   Default: the system clock.
     * @return The result of [MrzParser.parse], with the reading method set to `MANUAL_ENTRY`.
     */
    public fun read(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parse(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /**
     * Reads typed MRZ text as TD3 (passport).
     *
     * @param input The MRZ as one string, with the lines separated by a line break.
     * @param referenceTime The moment that picks the century of two-digit years and judges expiry.
     *   Default: the system clock.
     * @return The result of [MrzParser.parseTD3], with the reading method set to `MANUAL_ENTRY`.
     */
    public fun readTD3(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseTD3(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /**
     * Reads typed MRZ lines as TD3 (passport).
     *
     * @param input The MRZ lines.
     * @param referenceTime The moment that picks the century of two-digit years and judges expiry.
     *   Default: the system clock.
     * @return The result of [MrzParser.parseTD3], with the reading method set to `MANUAL_ENTRY`.
     */
    public fun readTD3(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseTD3(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /**
     * Reads typed MRZ text as TD2.
     *
     * @param input The MRZ as one string, with the lines separated by a line break.
     * @param referenceTime The moment that picks the century of two-digit years and judges expiry.
     *   Default: the system clock.
     * @return The result of [MrzParser.parseTD2], with the reading method set to `MANUAL_ENTRY`.
     */
    public fun readTD2(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseTD2(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /**
     * Reads typed MRZ lines as TD2.
     *
     * @param input The MRZ lines.
     * @param referenceTime The moment that picks the century of two-digit years and judges expiry.
     *   Default: the system clock.
     * @return The result of [MrzParser.parseTD2], with the reading method set to `MANUAL_ENTRY`.
     */
    public fun readTD2(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseTD2(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /**
     * Reads typed MRZ text as TD1 (identity card).
     *
     * @param input The MRZ as one string, with the lines separated by a line break.
     * @param referenceTime The moment that picks the century of two-digit years and judges expiry.
     *   Default: the system clock.
     * @return The result of [MrzParser.parseTD1], with the reading method set to `MANUAL_ENTRY`.
     */
    public fun readTD1(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseTD1(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /**
     * Reads typed MRZ lines as TD1 (identity card).
     *
     * @param input The MRZ lines.
     * @param referenceTime The moment that picks the century of two-digit years and judges expiry.
     *   Default: the system clock.
     * @return The result of [MrzParser.parseTD1], with the reading method set to `MANUAL_ENTRY`.
     */
    public fun readTD1(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseTD1(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /**
     * Reads typed MRZ text as MRV-A (Type-A visa).
     *
     * @param input The MRZ as one string, with the lines separated by a line break.
     * @param referenceTime The moment that picks the century of two-digit years and judges expiry.
     *   Default: the system clock.
     * @return The result of [MrzParser.parseMRVA], with the reading method set to `MANUAL_ENTRY`.
     */
    public fun readMRVA(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseMRVA(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /**
     * Reads typed MRZ lines as MRV-A (Type-A visa).
     *
     * @param input The MRZ lines.
     * @param referenceTime The moment that picks the century of two-digit years and judges expiry.
     *   Default: the system clock.
     * @return The result of [MrzParser.parseMRVA], with the reading method set to `MANUAL_ENTRY`.
     */
    public fun readMRVA(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseMRVA(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /**
     * Reads typed MRZ text as MRV-B (Type-B visa).
     *
     * @param input The MRZ as one string, with the lines separated by a line break.
     * @param referenceTime The moment that picks the century of two-digit years and judges expiry.
     *   Default: the system clock.
     * @return The result of [MrzParser.parseMRVB], with the reading method set to `MANUAL_ENTRY`.
     */
    public fun readMRVB(
        input: String,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseMRVB(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)

    /**
     * Reads typed MRZ lines as MRV-B (Type-B visa).
     *
     * @param input The MRZ lines.
     * @param referenceTime The moment that picks the century of two-digit years and judges expiry.
     *   Default: the system clock.
     * @return The result of [MrzParser.parseMRVB], with the reading method set to `MANUAL_ENTRY`.
     */
    public fun readMRVB(
        input: List<String>,
        referenceTime: Instant = Clock.System.now(),
    ): ParseResult = MrzParser.parseMRVB(input, referenceTime).withReadMethod(ReadMethod.MANUAL_ENTRY)
}

/**
 * Restamps [readMethod] onto a parser result, copying the metadata and changing only the read
 * method - the document, warnings, and validation failures are surfaced unchanged. Used by the
 * provenance-aware reading methods in `mrz-core` (e.g. [ManualMrzReader]) that delegate to
 * [MrzParser] - which always reports `BACKEND_STRING_INPUT` because it sees only a string - but
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
