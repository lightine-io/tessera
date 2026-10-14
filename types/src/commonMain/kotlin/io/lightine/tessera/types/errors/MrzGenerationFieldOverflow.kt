package io.lightine.tessera.types.errors

import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat

/**
 * Generation error: a value is longer than its field allows in the [format].
 *
 * The generator never shortens a value.
 *
 * @property format The format being generated.
 * @property field The field that overflowed.
 * @property maxLength Maximum length of the field, in characters.
 * @property observedLength Length of the supplied value, in characters.
 * @property observedValue The supplied value.
 * @since 0.1.0
 */
public data class MrzGenerationFieldOverflow(
    val format: MrzFormat,
    val field: MrzField,
    val maxLength: Int,
    val observedLength: Int,
    val observedValue: String,
) : MrzGenerationError() {
    /** English text for logs: the format, the field, both lengths, and the value. */
    override val description: String
        get() =
            "Format $format field ${this.field} expected at most $maxLength characters; " +
                "observed $observedLength characters: \"$observedValue\""
}
