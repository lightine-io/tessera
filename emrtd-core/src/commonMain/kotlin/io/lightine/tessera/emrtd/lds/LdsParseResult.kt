package io.lightine.tessera.emrtd.lds

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.ChipMalformedFile

/**
 * The outcome of turning the bytes of a chip file into a typed view.
 *
 * Parsing never throws for bad chip data: a file that is not built as ICAO Doc 9303 Part 10
 * describes gives [Failure]. Your app still has the bytes it passed in.
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
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
