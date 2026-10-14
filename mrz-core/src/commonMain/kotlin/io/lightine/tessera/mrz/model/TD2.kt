package io.lightine.tessera.mrz.model

import io.lightine.tessera.types.vocabulary.MrzFormat

/**
 * A parsed TD2 (smaller identity document) MRZ: two lines of 36 characters.
 *
 * @property rawLines The MRZ lines the document was read from.
 * @property commonFields The fields every format carries.
 * @property optionalData Optional data as printed: line 2, characters 29-35.
 * @since 0.1.0
 */
public data class TD2(
    override val rawLines: List<String>,
    override val commonFields: CommonFields,
    val optionalData: String,
) : MrzDocument() {
    /** Always [MrzFormat.TD2]. */
    override val format: MrzFormat = MrzFormat.TD2
}
