package io.lightine.tessera.types.errors

import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat

/**
 * Generation error: a value is shorter than its fixed-width field needs in the [format].
 *
 * Reported for a date part shorter than 2 characters. The generator never pads such a value:
 * a `<` in a date means "unknown", so padding would invent data.
 *
 * @property format The format being generated.
 * @property field The field that is too short.
 * @property expectedLength Length the field needs, in characters.
 * @property observedLength Length of the supplied value, in characters.
 * @property observedValue The supplied value.
 * @since 0.5.3
 */
public data class MrzGenerationFieldTooShort(
    val format: MrzFormat,
    val field: MrzField,
    val expectedLength: Int,
    val observedLength: Int,
    val observedValue: String,
) : MrzGenerationError() {
    /** English text for logs: the format, the field, and both lengths. It never contains the value; that is in [observedValue]. */
    override val description: String
        get() =
            "Format $format field ${this.field} expected exactly $expectedLength characters; " +
                "observed $observedLength characters"

    /** `MrzGenerationFieldTooShort: ` followed by [description], so logs carry no document values. */
    override fun toString(): String = "MrzGenerationFieldTooShort: $description"
}
