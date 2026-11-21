package io.lightine.tessera.emrtd.lds

import io.lightine.tessera.emrtd.internal.lds.LdsReader
import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.ChipMalformedFile
import io.lightine.tessera.types.vocabulary.ReadMethod
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * EF.DG1: the MRZ as the chip stores it (ICAO Doc 9303 Part 10, section 4.7.1), read with the same
 * [MrzParser] as a printed MRZ.
 *
 * The result is a [ParseResult.Success] or [ParseResult.PartialSuccess] whose
 * `metadata.readMethod` is [ReadMethod.NFC_CHIP]. Check digit findings and warnings are reported as
 * for any MRZ; nothing is corrected. The MRZ text (without line breaks) and the raw file bytes stay
 * available through [mrzText] and [raw].
 *
 * `toString()` never shows file content.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property mrzText The MRZ characters exactly as the chip holds them, with no line breaks: 90 for
 *   TD1, 72 for TD2, 88 for TD3.
 * @property parseResult The parser's result for the chip's MRZ: [ParseResult.Success] or
 *   [ParseResult.PartialSuccess], with the reading method set to [ReadMethod.NFC_CHIP].
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class Dg1View private constructor(
    private val rawBytes: ByteArray,
    public val mrzText: String,
    public val parseResult: ParseResult,
) {
    /** The parsed MRZ document. */
    public val document: MrzDocument
        get() =
            when (parseResult) {
                is ParseResult.Success -> parseResult.document

                is ParseResult.PartialSuccess -> parseResult.document

                // The only constructor path rejects a failed parse, so this cannot happen.
                is ParseResult.Failure -> error("Dg1View holds a failed parse")
            }

    /** A copy of the whole file as read from the chip. */
    public fun raw(): ByteArray = rawBytes.copyOf()

    /** The file's size; never its content. */
    override fun toString(): String = "Dg1View(${rawBytes.size} bytes)"

    /**
     * Reads EF.DG1.
     *
     * @since 1.0.0
     */
    public companion object {
        /** The tag EF.DG1 starts with. */
        public const val TAG: Int = 0x61

        private const val TAG_MRZ = 0x5F1F
        private const val FILE_ID = 0x0101

        /**
         * Reads the bytes of EF.DG1. Fails with a [ChipMalformedFile] result, never an exception,
         * when the file is not a DG1, the MRZ has a length no format uses, or the MRZ parser
         * rejects it.
         *
         * @param bytes the whole file.
         * @param referenceTime the instant that picks the century of two-digit years and sets the
         *   date warnings, as for [MrzParser.parse]. Defaults to the current time.
         */
        public fun parse(
            bytes: ByteArray,
            referenceTime: Instant = Clock.System.now(),
        ): LdsParseResult<Dg1View> =
            LdsReader.guard(FILE_ID) {
                val outer = LdsReader.open(bytes, FILE_ID, TAG)
                val mrzObject = outer.requireChild(TAG_MRZ)
                val text = mrzObject.value().decodeToString()
                val lines =
                    when (mrzObject.length) {
                        90 -> text.chunked(30)
                        72, 88 -> text.chunked(mrzObject.length / 2)
                        else -> LdsReader.fail(FILE_ID, ChipMalformedFile.Reason.INVALID_ELEMENT)
                    }
                // Non-ASCII bytes decode to characters the parser rejects; a multi-byte character would also
                // change the length, so check the character count matches the byte count.
                if (text.length != mrzObject.length) LdsReader.fail(FILE_ID, ChipMalformedFile.Reason.INVALID_ELEMENT)
                val result =
                    when (val parsed = MrzParser.parse(lines, referenceTime)) {
                        is ParseResult.Success -> parsed.copy(metadata = parsed.metadata.copy(readMethod = ReadMethod.NFC_CHIP))
                        is ParseResult.PartialSuccess -> parsed.copy(metadata = parsed.metadata.copy(readMethod = ReadMethod.NFC_CHIP))
                        is ParseResult.Failure -> LdsReader.fail(FILE_ID, ChipMalformedFile.Reason.INVALID_ELEMENT)
                    }
                Dg1View(bytes.copyOf(), text, result)
            }
    }
}
