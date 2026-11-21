package io.lightine.tessera.emrtd.lds

import io.lightine.tessera.emrtd.internal.lds.LdsReader
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.ChipMalformedFile

/**
 * EF.DG13: optional details the issuing state defines (ICAO Doc 9303 Part 10, section 4.7.13).
 *
 * The content is up to the issuer, so the SDK gives raw bytes only: the whole file through [raw]
 * and the value inside the `6D` object through [content].
 *
 * `toString()` never shows file content.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class Dg13View private constructor(
    private val rawBytes: ByteArray,
    private val contentBytes: ByteArray,
) {
    /** A copy of the whole file as read from the chip. */
    public fun raw(): ByteArray = rawBytes.copyOf()

    /** A copy of the value inside the `6D` object, exactly as the chip holds it. */
    public fun content(): ByteArray = contentBytes.copyOf()

    /** The file's size; never its content. */
    override fun toString(): String = "Dg13View(${rawBytes.size} bytes)"

    /**
     * Reads EF.DG13.
     *
     * @since 1.0.0
     */
    public companion object {
        /** The tag EF.DG13 starts with. */
        public const val TAG: Int = 0x6D

        private const val FILE_ID = 0x010D

        /**
         * Reads the bytes of EF.DG13. Fails with a [ChipMalformedFile] result, never an exception,
         * when the file does not start with tag `6D` or its length does not fit the bytes. The
         * content is not interpreted, so it may be any bytes.
         */
        public fun parse(bytes: ByteArray): LdsParseResult<Dg13View> =
            LdsReader.guard(FILE_ID) {
                val outer =
                    io.lightine.tessera.emrtd.internal.tlv
                        .TlvParser()
                        .parseSingle(bytes)
                if (outer.tag != TAG) LdsReader.fail(FILE_ID, ChipMalformedFile.Reason.UNEXPECTED_FILE_TAG)
                Dg13View(bytes.copyOf(), outer.value())
            }
    }
}
