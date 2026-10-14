package io.lightine.tessera.mrz.model

import io.lightine.tessera.types.vocabulary.MrzFormat

/**
 * A parsed TD1 (identity card) MRZ: three lines of 30 characters.
 *
 * @property rawLines The MRZ lines the document was read from.
 * @property commonFields The fields every format carries.
 * @property optionalData1 First optional-data field as printed: line 1, characters 16-30.
 * @property optionalData2 Second optional-data field as printed: line 2, characters 19-29.
 * @since 0.1.0
 */
public data class TD1(
    override val rawLines: List<String>,
    override val commonFields: CommonFields,
    val optionalData1: String,
    val optionalData2: String,
) : MrzDocument() {
    /** Always [MrzFormat.TD1]. */
    override val format: MrzFormat = MrzFormat.TD1
}
