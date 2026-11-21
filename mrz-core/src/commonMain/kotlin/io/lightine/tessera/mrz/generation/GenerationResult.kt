package io.lightine.tessera.mrz.generation

import io.lightine.tessera.mrz.parsing.ResultMetadata
import io.lightine.tessera.types.errors.MrzGenerationError

/**
 * The result of generating an MRZ: [Success] or [Failure].
 *
 * Success holds the MRZ lines of the document. `toString()` on each of them shows no document
 * values; they are in the properties.
 *
 * @since 0.1.0
 */
public sealed class GenerationResult {
    /** The reading method and transliteration details of this result. */
    public abstract val metadata: ResultMetadata

    /**
     * Generation produced the MRZ.
     *
     * @property mrz The MRZ lines, one string per line.
     * @property metadata The reading method and any transliteration details.
     * @since 0.1.0
     */
    public data class Success(
        val mrz: List<String>,
        override val metadata: ResultMetadata,
    ) : GenerationResult() {
        /** How many lines were produced; never the lines themselves. */
        override fun toString(): String = "Success(lines=${mrz.size})"
    }

    /**
     * Generation could not produce an MRZ.
     *
     * @property error Why generation failed.
     * @property metadata The reading method and any transliteration details.
     * @since 0.1.0
     */
    public data class Failure(
        val error: MrzGenerationError,
        override val metadata: ResultMetadata,
    ) : GenerationResult() {
        /** Why generation failed; never a document value. */
        override fun toString(): String = "Failure($error)"
    }
}
