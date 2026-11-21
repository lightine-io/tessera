package io.lightine.tessera.emrtd.lds

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.ChipMalformedFile

/**
 * The outcome of turning the bytes of a chip file into a typed view.
 *
 * Parsing never throws for bad chip data: a file that is not built as ICAO Doc 9303 Part 10
 * describes gives [Failure]. Your app still has the bytes it passed in.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public sealed class LdsParseResult<out T> {
    /**
     * The file was read.
     *
     * @property view The typed view.
     * @since 1.0.0
     */
    public class Success<out T>(
        public val view: T,
    ) : LdsParseResult<T>()

    /**
     * The file could not be read.
     *
     * @property error What is wrong with the file.
     * @since 1.0.0
     */
    public class Failure(
        public val error: ChipMalformedFile,
    ) : LdsParseResult<Nothing>()
}
