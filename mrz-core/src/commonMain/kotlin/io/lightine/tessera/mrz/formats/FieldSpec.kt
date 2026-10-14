package io.lightine.tessera.mrz.formats

/**
 * The position of one field in an MRZ format.
 *
 * @property line Zero-based index of the line.
 * @property startInLine Zero-based index of the first character on that line.
 * @property endInLineExclusive Zero-based index after the last character on that line.
 * @since 0.1.0
 */
public data class FieldSpec(
    public val line: Int,
    public val startInLine: Int,
    public val endInLineExclusive: Int,
) {
    /** Number of characters the field spans. */
    public val width: Int
        get() = endInLineExclusive - startInLine
}

/**
 * Returns this field's text from [lines].
 *
 * @param lines The MRZ lines.
 * @return The characters from [FieldSpec.startInLine] up to, not including,
 *   [FieldSpec.endInLineExclusive].
 */
public fun FieldSpec.extractFrom(lines: List<String>): String = lines[line].substring(startInLine, endInLineExclusive)

/**
 * Returns this field's first character from [lines].
 *
 * @param lines The MRZ lines.
 * @return The character at [FieldSpec.startInLine].
 */
public fun FieldSpec.extractCharFrom(lines: List<String>): Char = lines[line][startInLine]
